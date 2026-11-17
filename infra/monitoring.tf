# Cloud Run HTTP 5xx error-rate alert for EPIC-00-S010.
# Declared only. Do not apply until founder approval. Notification channels stay
# empty until an existing channel id is supplied; this file does not create one.

resource "google_monitoring_alert_policy" "cloud_run_http_5xx" {
  count        = var.admin_service_enabled ? 1 : 0
  project      = var.project_id
  display_name = "Platform Admin Cloud Run HTTP 5xx rate (${var.env})"
  combiner     = "OR"
  enabled      = true

  conditions {
    display_name = "Cloud Run HTTP 5xx rate is above zero"
    condition_threshold {
      filter          = "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"boss-admin-api\" AND metric.type=\"run.googleapis.com/request_count\" AND metric.labels.response_code_class=\"5xx\""
      duration        = "0s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      trigger {
        count = 1
      }
      aggregations {
        alignment_period   = "60s"
        per_series_aligner = "ALIGN_RATE"
      }
    }
  }

  notification_channels = var.monitoring_notification_channel_ids

  alert_strategy {
    auto_close = "1800s"
  }
}
