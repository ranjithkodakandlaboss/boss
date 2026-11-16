package in.boss.shared.rls;

import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BossConnectionWrapper tenant context injection (issue #12).
 *
 * Verifies:
 * - Null BossContext → empty string is set (secure default: RLS returns zero rows)
 * - Valid BossContext → correct UUID string is set
 * - SQLException from set_config propagates — connection never borrowed with stale context
 */
@ExtendWith(MockitoExtension.class)
class HikariExceptionSafetyTest {

    @Mock Connection connection;
    @Mock PreparedStatement preparedStatement;

    @AfterEach
    void cleanup() {
        BossContextHolder.clear();
    }

    @Test
    void noContext_setsEmptyStringAsTenantId() throws SQLException {
        BossContextHolder.clear();
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        BossConnectionWrapper.applyTenantContext(connection);

        verify(preparedStatement).setString(eq(1), captor.capture());
        assertThat(captor.getValue())
                .as("Empty-string tenant must be set so RLS returns zero rows")
                .isEmpty();
        verify(preparedStatement).execute();
    }

    @Test
    void withContext_setsTenantIdString() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, null, Set.of(), null, null));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        BossConnectionWrapper.applyTenantContext(connection);

        verify(preparedStatement).setString(eq(1), captor.capture());
        assertThat(captor.getValue()).isEqualTo(tenantId.toString());
        verify(preparedStatement).execute();
    }

    @Test
    void setConfigFailure_propagatesSQLException() throws SQLException {
        BossContextHolder.set(new BossContext(UUID.randomUUID(), null, Set.of(), null, null));

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        doThrow(new SQLException("DB connection broken")).when(preparedStatement).execute();

        // Exception must propagate so HikariCP does NOT hand over a connection
        // with an un-applied tenant context
        assertThatThrownBy(() -> BossConnectionWrapper.applyTenantContext(connection))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("DB connection broken");
    }

    @Test
    void setConfigFailure_onPrepare_propagatesSQLException() throws SQLException {
        BossContextHolder.set(new BossContext(UUID.randomUUID(), null, Set.of(), null, null));

        when(connection.prepareStatement(anyString()))
                .thenThrow(new SQLException("Cannot prepare statement"));

        assertThatThrownBy(() -> BossConnectionWrapper.applyTenantContext(connection))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Cannot prepare statement");
    }

    @Test
    void contextWithNullTenantId_setsEmptyString() throws SQLException {
        // BossContext present but tenantId is null (platform-level operation)
        BossContextHolder.set(new BossContext(null, UUID.randomUUID(), Set.of("PLATFORM_ADMIN"), null, null));
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        BossConnectionWrapper.applyTenantContext(connection);

        verify(preparedStatement).setString(eq(1), captor.capture());
        assertThat(captor.getValue())
                .as("Null tenantId must still produce empty-string (not null) for RLS")
                .isEmpty();
    }
}
