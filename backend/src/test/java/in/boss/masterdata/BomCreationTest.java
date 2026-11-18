package in.boss.masterdata;

import in.boss.masterdata.api.dto.BOMHeaderDto;
import in.boss.masterdata.api.dto.CreateBOMLineRequest;
import in.boss.masterdata.api.dto.CreateBOMRequest;
import in.boss.masterdata.api.dto.CreateItemRequest;
import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.ItemDto;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.BomService;
import in.boss.masterdata.domain.BomExplosionService;
import in.boss.masterdata.domain.ItemService;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BomCreationTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private BomService bomService;

    @Autowired
    private BomExplosionService bomExplosionService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private UomService uomService;

    private UUID tenantId;
    private ItemDto finishedGood;
    private ItemDto component1;
    private ItemDto component2;
    private UomDto uom;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("bom-create-test");
        setTenantContext(tenantId, "OWNER");

        uom = uomService.createUom(CreateUomRequest.builder()
                .name("Pieces")
                .symbol("PCS-" + UUID.randomUUID().toString().substring(0, 4))
                .uomType("quantity")
                .isBase(true)
                .build());

        finishedGood = itemService.createItem(CreateItemRequest.builder()
                .code("FG-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Finished Gearbox Assembly")
                .itemType("FINISHED_GOOD")
                .uomId(uom.getId())
                .build());

        component1 = itemService.createItem(CreateItemRequest.builder()
                .code("CP1-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Steel Gear 24T")
                .itemType("RAW_MATERIAL")
                .uomId(uom.getId())
                .build());

        component2 = itemService.createItem(CreateItemRequest.builder()
                .code("CP2-" + UUID.randomUUID().toString().substring(0, 6))
                .name("Ball Bearing 6004")
                .itemType("RAW_MATERIAL")
                .uomId(uom.getId())
                .build());
    }

    @Test
    void createBOM_andReviseBOM_supersedesPreviousVersion() {
        // 1. Initial BOM creation (Version 1)
        CreateBOMRequest requestV1 = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(component1.getId())
                                .qty(new BigDecimal("2.0"))
                                .uomId(uom.getId())
                                .scrapFactor(BigDecimal.ZERO)
                                .level(1)
                                .build(),
                        CreateBOMLineRequest.builder()
                                .componentItemId(component2.getId())
                                .qty(new BigDecimal("4.0"))
                                .uomId(uom.getId())
                                .scrapFactor(new BigDecimal("2.5"))
                                .level(1)
                                .build()
                ))
                .build();

        BOMHeaderDto bomV1 = bomService.createOrReviseBOM(finishedGood.getId(), requestV1);

        assertThat(bomV1.getId()).isNotNull();
        assertThat(bomV1.getItemId()).isEqualTo(finishedGood.getId());
        assertThat(bomV1.getVersion()).isEqualTo(1);
        assertThat(bomV1.getStatus()).isEqualTo("approved");
        assertThat(bomV1.getLines()).hasSize(2);

        // Active BOM query returns V1
        BOMHeaderDto activeBOM = bomService.getItemBOM(finishedGood.getId());
        assertThat(activeBOM.getId()).isEqualTo(bomV1.getId());
        assertThat(activeBOM.getVersion()).isEqualTo(1);

        // 2. Revise BOM (Version 2)
        CreateBOMRequest requestV2 = CreateBOMRequest.builder()
                .lines(List.of(
                        CreateBOMLineRequest.builder()
                                .componentItemId(component1.getId())
                                .qty(new BigDecimal("3.0")) // Changed qty
                                .uomId(uom.getId())
                                .level(1)
                                .build()
                ))
                .build();

        BOMHeaderDto bomV2 = bomService.createOrReviseBOM(finishedGood.getId(), requestV2);

        assertThat(bomV2.getId()).isNotEqualTo(bomV1.getId());
        assertThat(bomV2.getVersion()).isEqualTo(2);
        assertThat(bomV2.getStatus()).isEqualTo("approved");
        assertThat(bomV2.getLines()).hasSize(1);

        // Verify V1 status is now 'superseded' in DB
        String v1Status = jdbc.queryForObject(
                "SELECT status FROM bom_headers WHERE id = ?", String.class, bomV1.getId());
        assertThat(v1Status).isEqualTo("superseded");

        // Verify getItemBOM returns V2
        BOMHeaderDto currentActive = bomService.getItemBOM(finishedGood.getId());
        assertThat(currentActive.getId()).isEqualTo(bomV2.getId());
        assertThat(currentActive.getVersion()).isEqualTo(2);

        // Verify audit records
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_type = 'BOM'",
                Integer.class, tenantId);
        assertThat(auditCount).isGreaterThanOrEqualTo(2);
    }

    @Test
    void createBOM_withFormulaDoesNotRequireAFixedQuantity() {
        bomService.createOrReviseBOM(component1.getId(), CreateBOMRequest.builder()
                .lines(List.of(CreateBOMLineRequest.builder()
                        .componentItemId(component2.getId())
                        .qty(null)
                        .uomId(uom.getId())
                        .quantityFormula("thickness")
                        .build()))
                .build());
        BOMHeaderDto bom = bomService.createOrReviseBOM(finishedGood.getId(), CreateBOMRequest.builder()
                .lines(List.of(CreateBOMLineRequest.builder()
                        .componentItemId(component1.getId())
                        .qty(null)
                        .uomId(uom.getId())
                        .quantityFormula("length * width")
                        .build()))
                .build());

        assertThat(bom.getLines().get(0).getQuantityFormula()).isEqualTo("length * width");
        assertThat(bomExplosionService.requiredSpecificationNames(finishedGood.getId()))
                .containsExactlyInAnyOrder("length", "width", "thickness");
        assertThat(bomExplosionService.explodeBOM(finishedGood.getId(), new BigDecimal("2"),
                java.util.Map.of("length", new BigDecimal("2"), "width", new BigDecimal("3"),
                        "thickness", new BigDecimal("4")))
                .getFlatMaterials().get(0).getQty()).isEqualByComparingTo("48.0000");
    }
}
