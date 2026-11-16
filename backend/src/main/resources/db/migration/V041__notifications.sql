-- V041: Notifications and notification preferences (tenant-scoped)
CREATE TABLE notifications (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  user_id         UUID NOT NULL REFERENCES app_users(id),
  notification_type TEXT NOT NULL,
  entity_type     TEXT,
  entity_id       UUID,
  title           TEXT NOT NULL,
  body            TEXT,
  is_read         BOOLEAN NOT NULL DEFAULT FALSE,
  read_at         TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE notification_preferences (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  user_id         UUID NOT NULL REFERENCES app_users(id),
  notification_type TEXT NOT NULL,
  channel         TEXT NOT NULL CHECK (channel IN ('in_app','email','whatsapp')),
  enabled         BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, user_id, notification_type, channel)
);

CREATE INDEX idx_notifications_user     ON notifications(tenant_id, user_id, is_read);
CREATE INDEX idx_notif_prefs_user       ON notification_preferences(tenant_id, user_id);
