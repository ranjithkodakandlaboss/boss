package in.boss.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.pattern.PatternLayoutEncoderBase;
import java.net.URL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoggingConfigurationTest {

    private LoggerContext context;

    @BeforeEach
    void loadConfiguration() throws Exception {
        URL config = Thread.currentThread().getContextClassLoader().getResource("logback-spring.xml");
        assertThat(config).isNotNull();
        context = new LoggerContext();
        context.setName("logging-configuration-test");
        ch.qos.logback.classic.joran.JoranConfigurator configurator =
                new ch.qos.logback.classic.joran.JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(config);
    }

    @AfterEach
    void stopContext() {
        if (context != null) {
            context.stop();
        }
    }

    @Test
    void requestLoggerWritesTheMessageAloneSoTheLineStaysJson() {
        Logger requestLogger = context.getLogger(RequestLoggingFilter.LOGGER_NAME);
        assertThat(requestLogger.isAdditive()).isFalse();
        ConsoleAppender<?> appender = (ConsoleAppender<?>) requestLogger.getAppender("REQUEST_JSON");
        assertThat(appender).isNotNull();
        assertThat(encoderPattern(appender)).isEqualTo("%msg%n");
        assertThat(requestLogger.getAppender("APP_TEXT")).isNull();
    }

    @Test
    void applicationLoggerStaysPlainText() {
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        ConsoleAppender<?> appender = (ConsoleAppender<?>) root.getAppender("APP_TEXT");
        assertThat(appender).isNotNull();
        String pattern = encoderPattern(appender);
        assertThat(pattern).contains("level", "%logger", "%msg");
        assertThat(pattern).doesNotContain("json");

        LoggingEvent event = new LoggingEvent();
        event.setLoggerName("in.boss.shared.tenant.TenantStatusCache");
        event.setLevel(Level.INFO);
        event.setMessage("tenant cache refreshed");
        event.setTimeStamp(0L);
        event.setThreadName("main");
        String line = layout(pattern, event);
        assertThat(line).contains("INFO");
        assertThat(line).contains("in.boss.shared.tenant.TenantStatusCache");
        assertThat(line).contains("tenant cache refreshed");
        assertThat(line.trim()).doesNotStartWith("{");
    }

    private static String encoderPattern(ConsoleAppender<?> appender) {
        assertThat(appender.getEncoder()).isInstanceOf(PatternLayoutEncoder.class);
        return ((PatternLayoutEncoderBase<?>) appender.getEncoder()).getPattern();
    }

    private String layout(String pattern, LoggingEvent event) {
        ch.qos.logback.classic.PatternLayout layout = new ch.qos.logback.classic.PatternLayout();
        layout.setContext(context);
        layout.setPattern(pattern);
        layout.start();
        event.setLoggerContext(context);
        return layout.doLayout(event);
    }
}
