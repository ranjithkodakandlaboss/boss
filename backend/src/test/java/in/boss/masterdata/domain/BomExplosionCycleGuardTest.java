package in.boss.masterdata.domain;

import in.boss.masterdata.domain.impl.BomExplosionServiceImpl;
import in.boss.masterdata.persistence.BomHeaderEntity;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineEntity;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomExplosionCycleGuardTest {

    @Mock BomHeaderRepository bomHeaderRepo;
    @Mock BomLineRepository bomLineRepo;
    @Mock ItemRepository itemRepo;
    @Mock UnitOfMeasureRepository uomRepo;

    private BomExplosionService explosionService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        explosionService = new BomExplosionServiceImpl(bomHeaderRepo, bomLineRepo, itemRepo, uomRepo);
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
    }

    @AfterEach
    void tearDown() {
        BossContextHolder.clear();
    }

    @Test
    void cycleInBOM_cycleGuardAbortsWithCircularReferenceException() {
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();

        ItemEntity entA = ItemEntity.builder().id(itemA).tenantId(tenantId).code("A").build();
        when(itemRepo.findByIdAndTenantId(itemA, tenantId)).thenReturn(Optional.of(entA));

        // BOM A references B
        UUID bomAId = UUID.randomUUID();
        BomHeaderEntity bomA = BomHeaderEntity.builder().id(bomAId).tenantId(tenantId).finishedItemId(itemA).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemA, "approved")).thenReturn(Optional.of(bomA));
        when(bomLineRepo.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, bomAId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemB).quantity(BigDecimal.ONE).scrapPercent(BigDecimal.ZERO).uomId(uomId).level(1).build()
        ));

        // BOM B references A (loop!)
        UUID bomBId = UUID.randomUUID();
        BomHeaderEntity bomB = BomHeaderEntity.builder().id(bomBId).tenantId(tenantId).finishedItemId(itemB).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemB, "approved")).thenReturn(Optional.of(bomB));
        when(bomLineRepo.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, bomBId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemA).quantity(BigDecimal.ONE).scrapPercent(BigDecimal.ZERO).uomId(uomId).level(2).build()
        ));

        assertThatThrownBy(() -> explosionService.explodeBOM(itemA, BigDecimal.ONE))
                .isInstanceOf(CircularReferenceException.class)
                .hasMessageContaining("Cycle guard detected infinite loop");
    }
}
