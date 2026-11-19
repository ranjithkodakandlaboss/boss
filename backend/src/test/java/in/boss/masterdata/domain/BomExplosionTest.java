package in.boss.masterdata.domain;

import in.boss.masterdata.api.dto.BOMExplosionMaterialDto;
import in.boss.masterdata.api.dto.BOMExplosionResultDto;
import in.boss.masterdata.domain.impl.BomExplosionServiceImpl;
import in.boss.masterdata.domain.ValidationException;
import in.boss.masterdata.persistence.BomHeaderEntity;
import in.boss.masterdata.persistence.BomHeaderRepository;
import in.boss.masterdata.persistence.BomLineEntity;
import in.boss.masterdata.persistence.BomLineRepository;
import in.boss.masterdata.persistence.ItemEntity;
import in.boss.masterdata.persistence.ItemRepository;
import in.boss.masterdata.persistence.UnitOfMeasureEntity;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomExplosionTest {

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
    void explodeBOM_multiLevelWithScrap_computesQuantitiesCorrectly() {
        UUID itemA = UUID.randomUUID(); // Finished Good
        UUID itemB = UUID.randomUUID(); // Sub-assembly
        UUID itemR1 = UUID.randomUUID(); // Leaf raw material 1
        UUID itemR2 = UUID.randomUUID(); // Leaf raw material 2
        UUID itemR3 = UUID.randomUUID(); // Leaf raw material 3

        UUID uomId = UUID.randomUUID();

        ItemEntity entA = ItemEntity.builder().id(itemA).tenantId(tenantId).code("FG-A").name("Product A").build();
        ItemEntity entB = ItemEntity.builder().id(itemB).tenantId(tenantId).code("SA-B").name("Subassembly B").build();
        ItemEntity entR1 = ItemEntity.builder().id(itemR1).tenantId(tenantId).code("RM-1").name("Raw Material 1").build();
        ItemEntity entR2 = ItemEntity.builder().id(itemR2).tenantId(tenantId).code("RM-2").name("Raw Material 2").build();
        ItemEntity entR3 = ItemEntity.builder().id(itemR3).tenantId(tenantId).code("RM-3").name("Raw Material 3").build();

        UnitOfMeasureEntity uom = UnitOfMeasureEntity.builder().id(uomId).tenantId(tenantId).symbol("KG").build();

        when(itemRepo.findByIdAndTenantId(itemA, tenantId)).thenReturn(Optional.of(entA));
        when(itemRepo.findAllById(anyCollection())).thenReturn(List.of(entR1, entR2, entR3));
        when(uomRepo.findAllById(anyCollection())).thenReturn(List.of(uom));

        // BOM for A: 1 direct RM1 (qty 5, scrap 10%), 1 Subassembly B (qty 3, scrap 0%)
        UUID bomAId = UUID.randomUUID();
        BomHeaderEntity bomA = BomHeaderEntity.builder().id(bomAId).tenantId(tenantId).finishedItemId(itemA).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemA, "approved")).thenReturn(Optional.of(bomA));
        when(bomLineRepo.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, bomAId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemR1).quantity(new BigDecimal("5.0")).scrapPercent(new BigDecimal("10.0")).uomId(uomId).level(1).build(),
                BomLineEntity.builder().componentItemId(itemB).quantity(new BigDecimal("3.0")).scrapPercent(BigDecimal.ZERO).uomId(uomId).level(1).build()
        ));

        // BOM for B: RM2 (qty 4, scrap 0%), RM3 (qty 2, scrap 5%)
        UUID bomBId = UUID.randomUUID();
        BomHeaderEntity bomB = BomHeaderEntity.builder().id(bomBId).tenantId(tenantId).finishedItemId(itemB).status("approved").build();
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemB, "approved")).thenReturn(Optional.of(bomB));
        when(bomLineRepo.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, bomBId)).thenReturn(List.of(
                BomLineEntity.builder().componentItemId(itemR2).quantity(new BigDecimal("4.0")).scrapPercent(BigDecimal.ZERO).uomId(uomId).level(2).build(),
                BomLineEntity.builder().componentItemId(itemR3).quantity(new BigDecimal("2.0")).scrapPercent(new BigDecimal("5.0")).uomId(uomId).level(2).build()
        ));

        // Leaf items have no child BOMs
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemR1, "approved")).thenReturn(Optional.empty());
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemR2, "approved")).thenReturn(Optional.empty());
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, itemR3, "approved")).thenReturn(Optional.empty());

        // Explode for 2 units of A
        BOMExplosionResultDto result = explosionService.explodeBOM(itemA, new BigDecimal("2.0"));

        assertThat(result.getItemId()).isEqualTo(itemA);
        assertThat(result.getFlatMaterials()).hasSize(3);

        Map<UUID, BOMExplosionMaterialDto> materialMap = result.getFlatMaterials().stream()
                .collect(Collectors.toMap(BOMExplosionMaterialDto::getItemId, m -> m));

        // R1: 2 * 5 * 1.10 = 11.0000
        assertThat(materialMap.get(itemR1).getQty()).isEqualByComparingTo("11.0000");
        assertThat(materialMap.get(itemR1).getLevel()).isEqualTo(1);
        assertThat(materialMap.get(itemR1).getItemCode()).isEqualTo("RM-1");

        // R2: 2 * (3 * 4) = 24.0000
        assertThat(materialMap.get(itemR2).getQty()).isEqualByComparingTo("24.0000");
        assertThat(materialMap.get(itemR2).getLevel()).isEqualTo(2);

        // R3: 2 * (3 * 2 * 1.05) = 12.6000
        assertThat(materialMap.get(itemR3).getQty()).isEqualByComparingTo("12.6000");
        assertThat(materialMap.get(itemR3).getLevel()).isEqualTo(2);
    }

    @Test
    void explodeBOM_usesSpecificationFormulaAndReturnsRevisionSnapshot() {
        UUID finished = UUID.randomUUID();
        UUID material = UUID.randomUUID();
        UUID uomId = UUID.randomUUID();
        UUID headerId = UUID.randomUUID();
        BomHeaderEntity header = BomHeaderEntity.builder()
                .id(headerId).tenantId(tenantId).finishedItemId(finished).version("v3").status("approved").build();
        BomLineEntity line = BomLineEntity.builder()
                .componentItemId(material).quantity(BigDecimal.ONE).uomId(uomId)
                .quantityFormula("length * width / 1000").scrapPercent(BigDecimal.ZERO).level(1).build();
        ItemEntity finishedItem = ItemEntity.builder().id(finished).tenantId(tenantId).code("FG").build();
        ItemEntity materialItem = ItemEntity.builder().id(material).tenantId(tenantId).code("RM").build();
        UnitOfMeasureEntity uom = UnitOfMeasureEntity.builder().id(uomId).tenantId(tenantId).symbol("M2").build();
        when(itemRepo.findByIdAndTenantId(finished, tenantId)).thenReturn(Optional.of(finishedItem));
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, finished, "approved"))
                .thenReturn(Optional.of(header));
        when(bomHeaderRepo.findByTenantIdAndFinishedItemIdAndStatus(tenantId, material, "approved"))
                .thenReturn(Optional.empty());
        when(bomLineRepo.findByTenantIdAndBomHeaderIdOrderByLevelAscCreatedAtAsc(tenantId, headerId))
                .thenReturn(List.of(line));
        when(itemRepo.findAllById(anyCollection())).thenReturn(List.of(materialItem));
        when(uomRepo.findAllById(anyCollection())).thenReturn(List.of(uom));

        BOMExplosionResultDto result = explosionService.explodeBOM(finished, new BigDecimal("2"),
                Map.of("length", new BigDecimal("1200"), "width", new BigDecimal("500")));

        assertThat(result.getBomHeaderId()).isEqualTo(headerId);
        assertThat(result.getBomVersion()).isEqualTo("v3");
        assertThat(result.getSpecifications()).containsEntry("length", new BigDecimal("1200"));
        assertThat(result.getBomRevisions()).hasSize(1);
        assertThat(result.getFlatMaterials()).singleElement()
                .extracting(BOMExplosionMaterialDto::getQty).isEqualTo(new BigDecimal("1200.0000"));

        assertThatThrownBy(() -> explosionService.explodeBOM(finished, BigDecimal.ONE, Map.of()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("length");
    }
}
