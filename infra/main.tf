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

# ── Cloud SQL (PostgreSQL 15) ───────────────────────────────────────────────────
resource "google_sql_database_instance" "boss" {
  name                = "boss-${var.env}"
  database_version    = "POSTGRES_15"
  region              = var.region
  deletion_protection = var.env == "prod"

  settings {
    tier              = var.env == "prod" ? "db-custom-4-15360" : "db-f1-micro"
    availability_type = var.env == "prod" ? "REGIONAL" : "ZONAL"
    disk_autoresize   = true
    disk_size         = 10

    database_flags {
      name  = "max_connections"
      value = "100"
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "03:00"
      point_in_time_recovery_enabled = var.env == "prod"
    }

    ip_configuration {
      ipv4_enabled    = false
      private_network = google_compute_network.boss.id
    }
  }

  depends_on = [google_service_networking_connection.private_vpc]
}

resource "google_sql_database" "boss" {
  name     = "boss"
  instance = google_sql_database_instance.boss.name
}

resource "google_sql_user" "boss_app" {
  name     = "boss_app"
  instance = google_sql_database_instance.boss.name
  password = data.google_secret_manager_secret_version.db_password.secret_data
}

# ── Cloud Run ─────────────────────────────────────────────────────────────────
resource "google_cloud_run_v2_service" "boss_api" {
  name     = "boss-api"
  location = var.region
  project  = var.project_id

  template {
    service_account = google_service_account.boss_api.email
    scaling {
      min_instance_count = var.env == "prod" ? 1 : 0
      max_instance_count = var.env == "prod" ? 10 : 3
    }
    containers {
      image = "${var.region}-docker.pkg.dev/${var.project_id}/boss/boss-api:${var.image_tag}"
      ports { container_port = 8080 }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.env
      }
      env {
        name  = "FIREBASE_PROJECT_ID"
        value = var.customer_firebase_project_id
      }
      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }
      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }
    vpc_access {
      connector = google_vpc_access_connector.boss.id
      egress    = "ALL_TRAFFIC"
    }
  }
}

# Platform Admin runs as a separate Cloud Run service with its own identity,
# Firebase project, and database credential. Its HTTP auth remains Firebase JWT.
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
        value = "jdbc:postgresql://${google_sql_database_instance.boss.private_ip_address}:5432/${google_sql_database.boss.name}"
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
    vpc_access {
      connector = google_vpc_access_connector.boss.id
      egress    = "ALL_TRAFFIC"
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
resource "google_service_account" "boss_api" {
  account_id   = "boss-api-${var.env}"
  display_name = "BOSS API service account (${var.env})"
}

resource "google_service_account" "boss_admin_api" {
  account_id   = "boss-admin-api-${var.env}"
  display_name = "BOSS Platform Admin API service account (${var.env})"
}

resource "google_project_iam_member" "boss_api_secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.boss_api.email}"
}

resource "google_project_iam_member" "boss_api_sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.boss_api.email}"
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
resource "google_secret_manager_secret" "db_password" {
  secret_id = "boss-db-password-${var.env}"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret" "admin_db_password" {
  secret_id = "boss-admin-db-password-${var.env}"
  replication {
    auto {}
  }
}

data "google_secret_manager_secret_version" "db_password" {
  secret = google_secret_manager_secret.db_password.id
}

# ── VPC / Networking ──────────────────────────────────────────────────────────
resource "google_compute_network" "boss" {
  name                    = "boss-vpc-${var.env}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "boss" {
  name          = "boss-subnet-${var.env}"
  ip_cidr_range = "10.0.0.0/24"
  region        = var.region
  network       = google_compute_network.boss.id
}

resource "google_compute_global_address" "private_ip" {
  name          = "boss-private-ip-${var.env}"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.boss.id
}

resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.boss.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip.name]
}

resource "google_vpc_access_connector" "boss" {
  name          = "boss-vpc-conn-${var.env}"
  region        = var.region
  ip_cidr_range = "10.8.0.0/28"
  network       = google_compute_network.boss.name
}

output "boss_admin_api_url" {
  description = "URL of the separate Platform Admin Cloud Run service"
  value       = google_cloud_run_v2_service.boss_admin_api.uri
}
