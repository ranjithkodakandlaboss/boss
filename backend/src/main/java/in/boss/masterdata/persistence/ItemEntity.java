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
@Table(name = "items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "item_type", nullable = false)
    private String itemType;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "base_uom_id", nullable = false)
    private UUID baseUomId;

    @Column(name = "purchase_uom_id")
    private UUID purchaseUomId;

    @Column(name = "sales_uom_id")
    private UUID salesUomId;

    @Column(name = "hsn_code_id")
    private UUID hsnCodeId;

    @Column(name = "gst_rate")
    private BigDecimal gstRate;

    @Column(name = "min_stock_qty")
    private BigDecimal minStockQty;

    @Column(name = "reorder_qty")
    private BigDecimal reorderQty;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "std_cost")
    private BigDecimal stdCost;

    @Column(name = "last_purchase_price")
    private BigDecimal lastPurchasePrice;

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
