package in.boss.masterdata.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class ItemDependencyExceptionResolverTest {
    private final ItemDependencyExceptionResolver resolver = new ItemDependencyExceptionResolver(new ObjectMapper());

    @Test
    void rendersAnActionableConflictForItemDependencyFailures() throws Exception {
        var response = new MockHttpServletResponse();
        var exception = new RuntimeException(new SQLException("database details", "23B01"));

        assertThat(resolver.resolveException(new MockHttpServletRequest(), response, null, exception)).isNotNull();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("inactive or unavailable").doesNotContain("database details");
    }

    @Test
    void leavesOtherDatabaseFailuresToExistingHandlers() {
        var response = new MockHttpServletResponse();
        var exception = new RuntimeException(new SQLException("duplicate", "23505"));

        assertThat(resolver.resolveException(new MockHttpServletRequest(), response, null, exception)).isNull();
    }
}
