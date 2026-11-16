terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
  # State stored in GCS — bucket created manually once, then all state flows through it
  backend "gcs" {
    bucket = "boss-tofu-state"
    prefix = "terraform/state"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

resource "terraform_data" "distinct_firebase_projects" {
  input = {
    customer = var.customer_firebase_project_id
    admin    = var.admin_firebase_project_id
  }
  lifecycle {
    precondition {
      condition     = trimspace(var.customer_firebase_project_id) != trimspace(var.admin_firebase_project_id)
      error_message = "boss-api and boss-admin-api must use different Firebase projects."
    }
  }
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
  project = var.project_id
  name    = "boss-dev-postgres"
}

locals {
  boss_admin_db_url = "jdbc:postgresql:///boss?cloudSqlInstance=${var.project_id}:${var.region}:${data.google_sql_database_instance.boss_dev_postgres.name}&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC"
}

# Platform Admin runs as a separate Cloud Run service with its own identity,
# Firebase project, and database credential. It reuses the existing Cloud SQL
# instance and boss database; it does not create another SQL instance or a VPC.
resource "google_cloud_run_v2_service" "boss_admin_api" {
  name     = "boss-admin-api"
  location = var.region
  project  = var.project_id

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
    google_project_iam_member.boss_admin_api_sql_client,
    terraform_data.distinct_firebase_projects
  ]
}

resource "google_cloud_run_v2_service_iam_member" "boss_admin_api_public_invoker" {
  project  = var.project_id
  location = google_cloud_run_v2_service.boss_admin_api.location
  name     = google_cloud_run_v2_service.boss_admin_api.name
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
  value       = google_cloud_run_v2_service.boss_admin_api.uri
}
