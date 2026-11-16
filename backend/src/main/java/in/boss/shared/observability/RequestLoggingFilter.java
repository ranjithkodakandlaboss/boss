package in.boss.shared.observability;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Writes one structured JSON line per HTTP request on the {@code http.request} logger
 * and emits low-cardinality Micrometer metrics.
 * The query string and headers are omitted so tokens and PII are never written to Cloud Logging.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "http.request";
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public RequestLoggingFilter() {
        this(new ObjectMapper(), null);
    }

    public RequestLoggingFilter(ObjectMapper objectMapper) {
        this(objectMapper, null);
    }

    public RequestLoggingFilter(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;

        String correlationId = resolveCorrelationId(request);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        String traceId = extractTraceId(request);

        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            long elapsedNanos = System.nanoTime() - started;
            int status = resolvedStatus(response.getStatus(), failed);
            writeRequestLog(request, status, elapsedNanos, correlationId, traceId);
            recordMetrics(request.getMethod(), status, elapsedNanos);
        }
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String id = request.getHeader(CORRELATION_ID_HEADER);
        if (id == null || id.isBlank()) {
            id = request.getHeader("X-Request-ID");
        }
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    private String extractTraceId(HttpServletRequest request) {
        String cloudTrace = request.getHeader("X-Cloud-Trace-Context");
        if (cloudTrace != null && !cloudTrace.isBlank()) {
            int slash = cloudTrace.indexOf('/');
            return slash > 0 ? cloudTrace.substring(0, slash) : cloudTrace;
        }
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null && !traceparent.isBlank()) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2) {
                return parts[1];
            }
        }
        return null;
    }

    private void recordMetrics(String method, int status, long elapsedNanos) {
        if (meterRegistry == null) {
            return;
        }
        String statusStr = String.valueOf(status);
        Counter.builder("boss.http.requests")
                .tag("method", method)
                .tag("status", statusStr)
                .register(meterRegistry)
                .increment();

        Timer.builder("boss.http.latency")
                .tag("method", method)
                .tag("status", statusStr)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);

        if (status >= 400) {
            Counter.builder("boss.http.errors")
                    .tag("method", method)
                    .tag("status", statusStr)
                    .register(meterRegistry)
                    .increment();
        }
        if (status == 401) {
            Counter.builder("boss.auth.failures")
                    .register(meterRegistry)
                    .increment();
        } else if (status == 403) {
            Counter.builder("boss.auth.denials")
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void writeRequestLog(HttpServletRequest request, int status, long elapsedNanos,
                                 String correlationId, String traceId) {
        String line;
        try {
            line = objectMapper.writeValueAsString(new RequestLog(
                    "http_request",
                    severity(status),
                    request.getMethod() + " " + request.getRequestURI() + " " + status,
                    correlationId,
                    traceId,
                    new HttpRequestFields(
                            request.getMethod(),
                            request.getRequestURL().toString(),
                            status,
                            latency(elapsedNanos))));
        } catch (JsonProcessingException ex) {
            return;
        }
        if (status >= 500) {
            log.error(line);
        } else if (status >= 400) {
            log.warn(line);
        } else {
            log.info(line);
        }
    }

    static int resolvedStatus(int status, boolean failed) {
        if (status >= 100) {
            return failed && status < 400 ? 500 : status;
        }
        return failed ? 500 : 200;
    }

    static String severity(int status) {
        if (status >= 500) {
            return "ERROR";
        }
        if (status >= 400) {
            return "WARNING";
        }
        return "INFO";
    }

    static String latency(long elapsedNanos) {
        return String.format(Locale.US, "%.6fs", elapsedNanos / 1_000_000_000d);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RequestLog(String event, String severity, String message,
                              String correlationId, String traceId,
                              HttpRequestFields httpRequest) {
    }

    private record HttpRequestFields(String requestMethod, String requestUrl, int status, String latency) {
    }
}
