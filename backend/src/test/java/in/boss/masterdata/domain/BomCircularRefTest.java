package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateBOMLineRequest;
import in.boss.masterdata.api.dto.CreateBOMRequest;
import in.boss.masterdata.domain.impl.BomServiceImpl;
import in.boss.masterdata.persistence.BomHeaderEntity;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineEntity;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
import in.boss.masterdata.persistence.UnitOfMeasureRepository;
import in.boss.shared.audit.AuditService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomCircularRefTest {

    @Mock BomHeaderRepository bomHeaderRepo;
    @Mock BomLineRepository bomLineRepo;
    @Mock ItemRepository itemRepo;
    @Mock UnitOfMeasureRepository uomRepo;
    @Mock in.boss.masterdata.persistence.UomConversionRepository uomConversionRepo;
    @Mock AuditService auditService;

    private BomService bomService;
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bomService = new BomServiceImpl(bomHeaderRepo, bomLineRepo, itemRepo, uomRepo, uomConversionRepo, auditService, null, null);
        BossContextHolder.set(new BossContext(tenantId, UUID.randomUUID(), Set.of("OWNER"), null, null));
    }

    @AfterEach
    void tearDown() {
        BossContextHolder.clear();
    }

    @Test
    void selfReference_throwsCircularReferenceException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));

        CreateBOMRequest request = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(itemId) // Self-reference!
                                .qty(BigDecimal.ONE)
                                .uomId(UUID.randomUUID())
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> bomService.createOrReviseBOM(itemId, request))
                .isInstanceOf(CircularReferenceException.class)
                .hasMessageContaining("Item cannot reference itself in BOM");
    }

    @Test
    void indirectCircularReference_throwsCircularReferenceException() {
        // A -> B -> C -> A (cycle!)
        UUID itemA = UUID.randomUUID();
        UUID itemB = UUID.randomUUID();
        UUID itemC = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();

        ItemEntity entityA = ItemEntity.builder().id(itemA).tenantId(tenantId).isActive(true).build();
        ItemEntity entityB = ItemEntity.builder().id(itemB).tenantId(tenantId).isActive(true).build();
        UnitOfMeasureEntity uom = UnitOfMeasureEntity.builder().id(uomId).tenantId(tenantId).build();

        when(itemRepo.findByIdAndTenantId(itemA, tenantId)).thenReturn(Optional.of(entityA));
        when(itemRepo.findByIdAndTenantId(itemB, tenantId)).thenReturn(Optional.of(entityB));
        when(uomRepo.findByIdAndTenantId(uomId, tenantId)).thenReturn(Optional.of(uom));

        // Existing BOM for B: components include C
        UUID bomBId = UUID.randomUUID();
        BomHeaderEntity bomB = BomHeaderEntity.builder().id(bomBId).tenantId(tenantId).finishedItemId(itemB).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemB, "approved")).thenReturn(Optional.of(bomB));
        when(bomLineRepo.findByTenantIdAndBomHeaderId(tenantId, bomBId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemC).build()
        ));

        // Existing BOM for C: components include A!
        UUID bomCId = UUID.randomUUID();
        BomHeaderEntity bomC = BomHeaderEntity.builder().id(bomCId).tenantId(tenantId).finishedItemId(itemC).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemC, "approved")).thenReturn(Optional.of(bomC));
        when(bomLineRepo.findByTenantIdAndBomHeaderId(tenantId, bomCId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemA).build()
        ));

        // Propose BOM for A with component B
        CreateBOMRequest request = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(itemB)
                                .qty(BigDecimal.ONE)
                                .uomId(uomId)
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> bomService.createOrReviseBOM(itemA, request))
                .isInstanceOf(CircularReferenceException.class)
                .hasMessageContaining("Circular reference detected");
    }
}
