# Specification-driven BOM calculations

BOM lines may use either a fixed quantity or an optional `quantityFormula`. Formulas support decimal numbers, lowercase specification keys, parentheses, unary signs, and `+`, `-`, `*`, `/`. For example, `length * width / 1000000` calculates area when both dimensions are entered in millimetres and the component UOM is square metres. The formula result is the quantity per finished unit; the order quantity and line scrap factor are applied by the existing BOM explosion.

Use `POST /api/v1/items/{itemId}/bom/explode` with a positive `quantity` and a `specifications` object, for example:

```json
{
  "quantity": 3,
  "specifications": {
    "length": 1200,
    "width": 600
  }
}
```

The response contains the specification values, BOM header/version and revisions used, and the flattened material quantities. `GET /api/v1/items/{itemId}/bom/specifications` discovers all formula keys in the active BOM tree so the calculator can prompt for nested requirements. Missing formula values, invalid formula syntax, and non-positive computed component quantities are rejected.

RFQ lines store `specificationValues` as a map from stable lowercase keys to non-negative numbers. Quotation lines may reference their source `rfqLineId`; the server loads the RFQ's values and uses them for BOM costing. The persisted quotation costing snapshot retains the input values, BOM revisions, and calculated material quantities, so later BOM revisions do not rewrite an existing quotation's estimate.

Specification keys are intended to match Product Master attribute keys. This slice does not yet validate submitted values against Product Master ranges, defaults, or UOM metadata; formula authors must use a consistent unit convention. Cross-module definition/value validation and additional transaction snapshot integration are tracked in [#197](https://github.com/ranjithkodakandlaboss/boss/issues/197).

**Deployment ordering:** migration `V103__specification_driven_bom_snapshots.sql` is reserved for this work and must be applied only after migrations V101 and V102. Flyway out-of-order execution is disabled, so this change is not independently deployable until those predecessor migrations are present and applied.
