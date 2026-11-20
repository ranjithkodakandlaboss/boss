CREATE UNIQUE INDEX uq_customers_tenant_gstin
    ON customers (tenant_id, upper(btrim(gstin)))
    WHERE gstin IS NOT NULL AND btrim(gstin) <> '';
