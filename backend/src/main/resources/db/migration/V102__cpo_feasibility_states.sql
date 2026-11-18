ALTER TABLE cpos
    DROP CONSTRAINT IF EXISTS cpos_status_check;

ALTER TABLE cpos
    ADD CONSTRAINT cpos_status_check
        CHECK (status IN (
            'open', 'partial', 'fulfilled', 'cancelled', 'on_hold',
            'feasibility_pending', 'feasibility_approved'
        ));
