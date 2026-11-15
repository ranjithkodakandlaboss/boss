package in.boss.masterdata;

import in.boss.masterdata.api.dto.CreateUomRequest;
import in.boss.masterdata.api.dto.UomDto;
import in.boss.masterdata.domain.DuplicateEntityException;
import in.boss.masterdata.domain.EntityNotFoundException;
import in.boss.masterdata.domain.UomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UomCrudTest extends BaseMasterDataIntegrationTest {

    @Autowired
    private UomService uomService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = provisionTenant("uom-test");
        setTenantContext(tenantId, "OWNER");
    }

    @Test
    void createAndGetUom_success() {
        String symbol = "MTR-" + UUID.randomUUID().toString().substring(0, 4);
        CreateUomRequest request = CreateUomRequest.builder()
                .name("Meter")
                .symbol(symbol)
                .uomType("length")
                .isBase(true)
                .build();

        UomDto created = uomService.createUom(request);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getName()).isEqualTo("Meter");
        assertThat(created.getSymbol()).isEqualTo(symbol);
        assertThat(created.getUomType()).isEqualTo("length");
        assertThat(created.getIsBase()).isTrue();

        UomDto fetched = uomService.getUom(created.getId());
        assertThat(fetched.getId()).isEqualTo(created.getId());
        assertThat(fetched.getSymbol()).isEqualTo(symbol);

        // Verify audit event
        int auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = ? AND entity_id = ?",
                Integer.class, tenantId, created.getId());
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void duplicateSymbol_throwsDuplicateEntityException() {
        String symbol = "LITRE-" + UUID.randomUUID().toString().substring(0, 4);
        CreateUomRequest request = CreateUomRequest.builder()
                .name("Litre")
                .symbol(symbol)
                .uomType("volume")
                .isBase(true)
                .build();

        uomService.createUom(request);

        CreateUomRequest duplicate = CreateUomRequest.builder()
                .name("Litre Duplicate")
                .symbol(symbol)
                .uomType("volume")
                .isBase(false)
                .build();

        assertThatThrownBy(() -> uomService.createUom(duplicate))
                .isInstanceOf(DuplicateEntityException.class)
                .hasMessageContaining("UOM symbol already exists");
    }

    @Test
    void listUoms_filterByType() {
        String symWeight = "G-" + UUID.randomUUID().toString().substring(0, 4);
        String symVolume = "ML-" + UUID.randomUUID().toString().substring(0, 4);

        uomService.createUom(CreateUomRequest.builder().name("Gram").symbol(symWeight).uomType("weight").build());
        uomService.createUom(CreateUomRequest.builder().name("Millilitre").symbol(symVolume).uomType("volume").build());

        List<UomDto> weightList = uomService.listUoms("weight");
        assertThat(weightList).anyMatch(u -> u.getSymbol().equals(symWeight));
        assertThat(weightList).noneMatch(u -> u.getSymbol().equals(symVolume));
    }
}
