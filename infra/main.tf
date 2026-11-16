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
  name             = "boss-${var.env}"
  database_version = "POSTGRES_15"
  region           = var.region
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

# Allow unauthenticated invocation so the public internet can reach the API.
# Spring Security enforces Firebase JWT auth on all endpoints except the health probe.
resource "google_cloud_run_v2_service_iam_member" "boss_api_public_invoker" {
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.boss_api.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# ── Service Accounts ──────────────────────────────────────────────────────────
resource "google_service_account" "boss_api" {
  account_id   = "boss-api-${var.env}"
  display_name = "BOSS API service account (${var.env})"
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

# ── Secret Manager ────────────────────────────────────────────────────────────
resource "google_secret_manager_secret" "db_password" {
  secret_id = "boss-db-password-${var.env}"
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
