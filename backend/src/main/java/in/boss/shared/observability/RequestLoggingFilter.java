package in.boss.shared.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes one structured JSON line per HTTP request on the {@code http.request} logger.
 * The query string and headers are omitted so tokens are not written to Cloud Logging.
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "http.request";

    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

    private final ObjectMapper objectMapper;

    public RequestLoggingFilter() {
        this(new ObjectMapper());
    }

    RequestLoggingFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException ex) {
            failed = true;
            throw ex;
        } finally {
            writeRequestLog(request, resolvedStatus(response.getStatus(), failed), System.nanoTime() - started);
        }
    }

    private void writeRequestLog(HttpServletRequest request, int status, long elapsedNanos) {
        String line;
        try {
            line = objectMapper.writeValueAsString(new RequestLog(
                    "http_request",
                    severity(status),
                    request.getMethod() + " " + request.getRequestURI() + " " + status,
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

    private record RequestLog(String event, String severity, String message, HttpRequestFields httpRequest) {
    }

    private record HttpRequestFields(String requestMethod, String requestUrl, int status, String latency) {
    }
}
