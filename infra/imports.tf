# ── OpenTofu import blocks for existing GCP resources ─────────────────────────
#
# These blocks tell OpenTofu to adopt existing resources into state rather than
# creating new ones. Run once; remove after `tofu apply` completes successfully.
#
# Resources confirmed by user/cloudbuild.yaml as already deployed:
#   Cloud Run:          boss-api-dev
#   Artifact Registry:  boss-docker
#   Cloud SQL instance: boss-dev-postgres
#
# Resources assumed to follow IaC naming (verify before apply):
#   Cloud SQL database: boss
#   Cloud SQL user:     boss_app
#   Service account:    boss-api-dev
#   VPC:                boss-vpc-dev
#   Subnet:             boss-subnet-dev
#   Private IP range:   boss-private-ip-dev
#   VPC connector:      boss-vpc-conn-dev
#   Secret:             boss-db-password-dev

# ── Confirmed: Cloud Run ───────────────────────────────────────────────────────
import {
  to = google_cloud_run_v2_service.boss_api
  id = "projects/boss-dev-514813/locations/asia-south1/services/boss-api-dev"
}

# ── Confirmed: Artifact Registry ──────────────────────────────────────────────
import {
  to = google_artifact_registry_repository.boss
  id = "projects/boss-dev-514813/locations/asia-south1/repositories/boss-docker"
}

# ── Confirmed: Cloud SQL instance ─────────────────────────────────────────────
import {
  to = google_sql_database_instance.boss
  id = "boss-dev-514813/boss-dev-postgres"
}

# ── Assumed: Cloud SQL database ───────────────────────────────────────────────
# Comment out if the database "boss" does not yet exist in boss-dev-postgres
import {
  to = google_sql_database.boss
  id = "boss-dev-514813/boss-dev-postgres/boss"
}

# ── Assumed: Cloud SQL user ────────────────────────────────────────────────────
# Comment out if the user "boss_app" does not yet exist
import {
  to = google_sql_user.boss_app
  id = "boss-dev-514813/boss-dev-postgres//boss_app/"
}

# ── Assumed: Service account ──────────────────────────────────────────────────
# Comment out if the SA "boss-api-dev" does not yet exist
import {
  to = google_service_account.boss_api
  id = "projects/boss-dev-514813/serviceAccounts/boss-api-dev@boss-dev-514813.iam.gserviceaccount.com"
}

# ── Assumed: VPC network ──────────────────────────────────────────────────────
# Comment out if the VPC "boss-vpc-dev" does not yet exist
import {
  to = google_compute_network.boss
  id = "projects/boss-dev-514813/global/networks/boss-vpc-dev"
}

# ── Assumed: Subnet ───────────────────────────────────────────────────────────
# Comment out if the subnet "boss-subnet-dev" does not yet exist
import {
  to = google_compute_subnetwork.boss
  id = "projects/boss-dev-514813/regions/asia-south1/subnetworks/boss-subnet-dev"
}

# ── Assumed: Private IP range ─────────────────────────────────────────────────
# Comment out if the global address "boss-private-ip-dev" does not yet exist
import {
  to = google_compute_global_address.private_ip
  id = "projects/boss-dev-514813/global/addresses/boss-private-ip-dev"
}

# ── Assumed: VPC connector ────────────────────────────────────────────────────
# Comment out if the connector "boss-vpc-conn-dev" does not yet exist
import {
  to = google_vpc_access_connector.boss
  id = "projects/boss-dev-514813/locations/asia-south1/connectors/boss-vpc-conn-dev"
}

# ── Assumed: Secret Manager secret ────────────────────────────────────────────
# Comment out if the secret "boss-db-password-dev" does not yet exist
import {
  to = google_secret_manager_secret.db_password
  id = "projects/boss-dev-514813/secrets/boss-db-password-dev"
}

# ── NOT imported — will be created fresh ──────────────────────────────────────
# google_project_iam_member.boss_api_secret_accessor  (IAM binding)
# google_project_iam_member.boss_api_sql_client       (IAM binding)
# google_service_networking_connection.private_vpc    (peering connection)
# google_cloud_run_v2_service_iam_member.boss_api_public_invoker  (new binding)
