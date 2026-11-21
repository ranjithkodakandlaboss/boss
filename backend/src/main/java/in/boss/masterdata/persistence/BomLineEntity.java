package in.boss.masterdata.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bom_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BomLineEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "bom_header_id", nullable = false)
    private UUID bomHeaderId;

    @Column(name = "parent_line_id")
    private UUID parentLineId;

    @Column(name = "component_item_id", nullable = false)
    private UUID componentItemId;

    @Column(name = "quantity", nullable = false)
    private BigDecimal quantity;

    @Column(name = "uom_id", nullable = false)
    private UUID uomId;

    @Column(name = "level", nullable = false)
    private Integer level;

    @Column(name = "scrap_percent", nullable = false)
    private BigDecimal scrapPercent;

    @Column(name = "is_optional", nullable = false)
    private boolean isOptional;

    @Column(name = "operation_ref")
    private String operationRef;

    @Column(name = "notes")
    private String notes;

    @Column(name = "quantity_formula")
    private String quantityFormula;

    @Column(name = "unit_cost")
    private BigDecimal unitCost;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
