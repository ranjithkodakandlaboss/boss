# Product specification definitions

The existing item API accepts optional `productKind` (`MANUFACTURED` or `TRADED`)
and `specifications` when creating a `FINISHED_GOOD`. Existing items remain
unclassified with an empty list. No BOM is required by this configuration.

Each numeric definition has a unique lowercase `key` matching
`[a-z][a-z0-9_]{0,49}`, a label, a tenant-owned `uomId`, and optional
`minValue`, `maxValue`, and `defaultValue`. Values allow at most 18 digits
and 6 decimal places. The API returns the unit symbol as a snapshot; keys are
intended to match the keys of a transaction's `specificationValues` map.
Definitions and product kind cannot be changed through the item API after
creation, including for previously created items.

These are definitions only: transaction entry, bounds validation, variants,
snapshots, formula resolution, and manufactured/traded workflow behavior are
not implemented here. Existing item deactivation/deletion only checks active
BOM references, not all operational dependencies or concurrent writes. Do not
rely on it as a complete lifecycle safeguard. Cross-module validation and
snapshots are tracked in #197.

Migration V109 must be rolled out only after V101-V108 have been applied.
Flyway out-of-order migrations are disabled; deploying V109 first would prevent
the earlier migrations from being applied normally.
