package in.boss.inventory.domain;

import in.boss.inventory.api.InventoryApiDtos.JobLotReservationResponse;
import in.boss.inventory.api.InventoryApiDtos.LotReservationResponse;
import in.boss.inventory.persistence.InventoryReservationRepository;
import in.boss.inventory.persistence.InventoryReservationRepository.ActiveLotReservation;
import in.boss.inventory.persistence.InventoryReservationRepository.LotReservationRow;
import in.boss.shared.context.BossContext;
import in.boss.shared.context.BossContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryLotReservationQueryTest {

    @Mock
    private InventoryReservationRepository reservationRepository;

    private InventoryLotReservationQuery query;

    @BeforeEach
    void setUp() {
        query = new InventoryLotReservationQuery(reservationRepository);
    }

    @AfterEach
    void tearDown() {
        BossContextHolder.clear();
    }

    @Test
    void listLotReservations_requiresContext() {
        BossContextHolder.clear();
        UUID lotId = UUID.randomUUID();
        assertThatThrownBy(() -> query.listLotReservations(lotId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant context is required");
    }

    @Test
    void listLotReservations_returnsActiveReservations() {
        UUID tenantId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("STORE_KEEPER"), null, "keeper@example.com"));

        when(reservationRepository.activeLotReservations(tenantId, lotId)).thenReturn(List.of(
                new LotReservationRow(resId, "JOB", refId, new BigDecimal("5.00"), "DOC-123")
        ));

        List<LotReservationResponse> result = query.listLotReservations(lotId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(resId);
        assertThat(result.get(0).referenceType()).isEqualTo("JOB");
        assertThat(result.get(0).referenceId()).isEqualTo(refId);
        assertThat(result.get(0).reservedQty()).isEqualByComparingTo("5.00");
        assertThat(result.get(0).documentNumber()).isEqualTo("DOC-123");
    }

    @Test
    void listJobLotReservations_requiresContext() {
        BossContextHolder.clear();
        UUID jobId = UUID.randomUUID();
        assertThatThrownBy(() -> query.listJobLotReservations(jobId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Tenant context is required");
    }

    @Test
    void listJobLotReservations_returnsActiveReservations() {
        UUID tenantId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("STORE_KEEPER"), siteId, "keeper@example.com"));

        when(reservationRepository.activeProductionLotReservations(tenantId, jobId, siteId)).thenReturn(List.of(
                new ActiveLotReservation(jobId, lotId, itemId, new BigDecimal("3.00"))
        ));

        List<JobLotReservationResponse> result = query.listJobLotReservations(jobId);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).jobId()).isEqualTo(jobId);
        assertThat(result.get(0).lotId()).isEqualTo(lotId);
        assertThat(result.get(0).itemId()).isEqualTo(itemId);
        assertThat(result.get(0).reservedQty()).isEqualByComparingTo("3.00");
    }
}
