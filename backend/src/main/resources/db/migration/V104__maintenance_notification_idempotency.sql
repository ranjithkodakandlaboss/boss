-- V104: Ensure a maintenance impact alert is created at most once per event recipient.
-- Roll out only after V101-V103; Flyway out-of-order execution is disabled.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_maintenance_impact_recipient
  ON notifications (tenant_id, user_id, notification_type, entity_type, entity_id)
  WHERE notification_type = 'maintenance_asset_impact'
    AND entity_type = 'maintenance_event'
    AND entity_id IS NOT NULL;
