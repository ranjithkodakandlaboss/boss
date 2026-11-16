package in.boss.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private RequestLoggingFilter filter;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        filter = new RequestLoggingFilter();
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void writesOneStructuredJsonLineForASuccessfulRequest() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/items");
        request.setQueryString("access_token=secret-value");
        request.addHeader("Authorization", "Bearer secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (rq, rs) -> ((HttpServletResponse) rs).setStatus(204));

        JsonNode line = onlyRequestLine();
        assertThat(line.get("event").asText()).isEqualTo("http_request");
        assertThat(line.get("severity").asText()).isEqualTo("INFO");
        assertThat(line.get("message").asText()).isEqualTo("GET /api/v1/items 204");
        assertThat(line.get("httpRequest").get("requestMethod").asText()).isEqualTo("GET");
        assertThat(line.get("httpRequest").get("requestUrl").asText()).isEqualTo("http://localhost/api/v1/items");
        assertThat(line.get("httpRequest").get("status").asInt()).isEqualTo(204);
        assertThat(line.get("httpRequest").get("latency").asText()).endsWith("s");
        assertThat(line.toString()).doesNotContain("secret-value", "secret-token", "Authorization");
    }

    @Test
    void recordsClientErrorsAsWarning() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("POST", "/api/v1/items"), response, (rq, rs) -> ((HttpServletResponse) rs).setStatus(422));

        JsonNode line = onlyRequestLine();
        assertThat(line.get("severity").asText()).isEqualTo("WARNING");
        assertThat(line.get("httpRequest").get("status").asInt()).isEqualTo(422);
        assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("WARN");
    }

    @Test
    void recordsServerErrorsAsError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/api/v1/items"), response, (rq, rs) -> ((HttpServletResponse) rs).setStatus(503));

        JsonNode line = onlyRequestLine();
        assertThat(line.get("severity").asText()).isEqualTo("ERROR");
        assertThat(line.get("message").asText()).isEqualTo("GET /api/v1/items 503");
        assertThat(line.get("httpRequest").get("status").asInt()).isEqualTo(503);
        assertThat(appender.list.get(0).getLevel().toString()).isEqualTo("ERROR");
    }

    @Test
    void recordsAnEscapingFailureAsHttp500AndRethrows() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request("GET", "/api/v1/items"), response,
                (rq, rs) -> {
                    throw new ServletException("boom");
                }))
                .isInstanceOf(ServletException.class)
                .hasMessage("boom");

        assertThat(onlyRequestLine().get("httpRequest").get("status").asInt()).isEqualTo(500);
        assertThat(onlyRequestLine().get("severity").asText()).isEqualTo("ERROR");
    }

    @Test
    void keepsAStatusAlreadySetWhenTheChainFails() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request("GET", "/api/v1/items"), response,
                (rq, rs) -> {
                    ((HttpServletResponse) rs).setStatus(503);
                    throw new ServletException("unavailable");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(onlyRequestLine().get("httpRequest").get("status").asInt()).isEqualTo(503);
    }

    private JsonNode onlyRequestLine() throws Exception {
        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("\n");
        return objectMapper.readTree(message);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }
}
