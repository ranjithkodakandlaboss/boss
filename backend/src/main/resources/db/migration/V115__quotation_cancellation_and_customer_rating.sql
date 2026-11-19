ALTER TABLE quotations DROP CONSTRAINT quotations_status_check;
ALTER TABLE quotations ADD CONSTRAINT quotations_status_check
    CHECK (status IN ('draft','sent','negotiating','accepted','rejected','expired','superseded','cancelled'));

ALTER TABLE customers ADD COLUMN rating INTEGER CHECK (rating BETWEEN 0 AND 5);

