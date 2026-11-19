ALTER TABLE vault_documents DROP CONSTRAINT vault_documents_category_check;

ALTER TABLE vault_documents ADD CONSTRAINT vault_documents_category_check
    CHECK (category IN (
        'Job Documents',
        'Quality Documents',
        'Material Certificates',
        'Supplier Documents',
        'Company Certificates',
        'Asset Documents',
        'Customer Documents',
        'Sales Documents'
    ));
