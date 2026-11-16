terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
  # This root is dedicated to the Platform Admin service only. Keep its state path
  # separate from the customer Terraform ownership instead of reusing the legacy
  # shared prefix. The operator must create or approve the bucket and the
  # dedicated state key before a real plan runs.
  backend "gcs" {
    bucket = "boss-tofu-state"
    prefix = "terraform/state/platform-admin/dev"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# ── Artifact Registry ──────────────────────────────────────────────────────────
resource "google_artifact_registry_repository" "boss" {
  project       = var.project_id
  location      = var.region
  repository_id = "boss"
  description   = "BOSS Docker images"
  format        = "DOCKER"
}

# The live Dev environment already contains the customer Cloud SQL instance and
# database. Only the separate admin service and its Secret Manager wiring are
# provisioned here; no additional SQL instance, private networking, or customer
# resources are created.
data "google_sql_database_instance" "boss_dev_postgres" {
  count   = var.admin_service_enabled ? 1 : 0
  project = var.project_id
  name    = "boss-dev-postgres"
}

locals {
  boss_admin_db_url = var.admin_service_enabled ? "jdbc:postgresql:///boss?cloudSqlInstance=${var.project_id}:${var.region}:${data.google_sql_database_instance.boss_dev_postgres[0].name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC" : ""
}

# Platform Admin runs as a separate Cloud Run service with its own identity,
# Firebase project, and database credential. It reuses the existing Cloud SQL
# instance and boss database; it does not create another SQL instance or a VPC.
# Stage 1 leaves this disabled while bootstrapping its repository, identity,
# secret, and IAM. Stage 2 enables it after the bootstrap image and prerequisites
# are ready.
resource "google_cloud_run_v2_service" "boss_admin_api" {
  count    = var.admin_service_enabled ? 1 : 0
  name     = "boss-admin-api"
  location = var.region
  project  = var.project_id

  lifecycle {
    prevent_destroy = true

    precondition {
      condition = (
        length(trimspace(var.admin_firebase_project_id)) > 0 &&
        trimspace(var.customer_firebase_project_id) != trimspace(var.admin_firebase_project_id)
      )
      error_message = "Set a non-empty Admin Firebase project ID distinct from the customer Firebase project before enabling the Admin service."
    }

    # GitHub Actions owns image rollouts after initial service creation;
    # OpenTofu continues to own the service configuration.
    ignore_changes = [template[0].containers[0].image]
  }

  template {
    service_account = google_service_account.boss_admin_api.email
    scaling {
      min_instance_count = 0
      max_instance_count = var.env == "prod" ? 5 : 2
    }
    volumes {
      name = "admin-db-password"
      secret {
        secret = google_secret_manager_secret.admin_db_password.secret_id
        items {
          version = "latest"
          path    = "password"
        }
      }
    }
    containers {
      image = "${var.region}-docker.pkg.dev/${var.project_id}/boss/boss-admin-api:${var.admin_image_tag}"
      ports { container_port = 8080 }
      volume_mounts {
        name       = "admin-db-password"
        mount_path = "/secrets"
      }
      env {
        name  = "ADMIN_DB_URL"
        value = local.boss_admin_db_url
      }
      env {
        name  = "ADMIN_DB_USER"
        value = "boss_admin"
      }
      env {
        name  = "ADMIN_DB_PASSWORD_FILE"
        value = "/secrets/password"
      }
      env {
        name  = "ADMIN_FIREBASE_PROJECT_ID"
        value = var.admin_firebase_project_id
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.env
      }
      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }
  }
  depends_on = [
    google_secret_manager_secret_iam_member.boss_admin_api_secret_accessor,
    google_project_iam_member.boss_admin_api_sql_client
  ]
}

resource "google_cloud_run_v2_service_iam_member" "boss_admin_api_public_invoker" {
  count    = var.admin_service_enabled ? 1 : 0
  project  = var.project_id
  location = google_cloud_run_v2_service.boss_admin_api[0].location
  name     = google_cloud_run_v2_service.boss_admin_api[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# ── Service Accounts ──────────────────────────────────────────────────────────
resource "google_service_account" "boss_admin_api" {
  account_id   = "boss-admin-api-${var.env}"
  display_name = "BOSS Platform Admin API service account (${var.env})"
}

resource "google_secret_manager_secret_iam_member" "boss_admin_api_secret_accessor" {
  secret_id = google_secret_manager_secret.admin_db_password.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.boss_admin_api.email}"
}

resource "google_project_iam_member" "boss_admin_api_sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.boss_admin_api.email}"
}

# ── Secret Manager ────────────────────────────────────────────────────────────
resource "google_secret_manager_secret" "admin_db_password" {
  secret_id = "boss-admin-db-password-${var.env}"
  replication {
    auto {}
  }
}

output "boss_admin_api_url" {
  description = "URL of the separate Platform Admin Cloud Run service"
  value       = try(google_cloud_run_v2_service.boss_admin_api[0].uri, null)
}
