package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.CreateBOMLineRequest;
import in.boss.masterdata.api.dto.CreateBOMRequest;
import in.boss.masterdata.domain.impl.BomServiceImpl;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomMaxLevelTest {

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
    void declaredLevel_greaterThan5_throwsMaxBomLevelExceededException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));

        CreateBOMRequest request = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(UUID.randomUUID())
                                .qty(BigDecimal.ONE)
                                .uomId(UUID.randomUUID())
                                .level(6) // Level 6 exceeds max 5!
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> bomService.createOrReviseBOM(itemId, request))
                .isInstanceOf(MaxBomLevelExceededException.class)
                .hasMessageContaining("BOM level must be between 1 and 5, received: 6");
    }

    @Test
    void declaredLevel_lessThan1_throwsMaxBomLevelExceededException() {
        UUID itemId = UUID.randomUUID();
        ItemEntity item = ItemEntity.builder().id(itemId).tenantId(tenantId).isActive(true).build();
        when(itemRepo.findByIdAndTenantId(itemId, tenantId)).thenReturn(Optional.of(item));

        CreateBOMRequest request = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(UUID.randomUUID())
                                .qty(BigDecimal.ONE)
                                .uomId(UUID.randomUUID())
                                .level(0) // Level 0 invalid!
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> bomService.createOrReviseBOM(itemId, request))
                .isInstanceOf(MaxBomLevelExceededException.class)
                .hasMessageContaining("BOM level must be between 1 and 5, received: 0");
    }
}
