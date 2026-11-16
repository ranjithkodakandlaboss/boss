#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set}"
: "${GCP_REGION:?GCP_REGION must be set}"
: "${ARTIFACT_REGISTRY_REPOSITORY:?ARTIFACT_REGISTRY_REPOSITORY must be set}"
: "${CLOUD_RUN_SERVICE:?CLOUD_RUN_SERVICE must be set}"
: "${ADMIN_DB_SECRET_NAME:?ADMIN_DB_SECRET_NAME must be set}"

if ! gcloud artifacts repositories describe "${ARTIFACT_REGISTRY_REPOSITORY}" \
  --location "${GCP_REGION}" \
  --project "${GCP_PROJECT_ID}" \
  --format='value(name)' >/dev/null; then
  echo "::error::Artifact Registry repository '${ARTIFACT_REGISTRY_REPOSITORY}' is missing in ${GCP_REGION}. Apply the OpenTofu infrastructure first."
  exit 1
fi

service_json="$(mktemp)"
trap 'rm -f "${service_json}"' EXIT
if ! gcloud run services describe "${CLOUD_RUN_SERVICE}" \
  --region "${GCP_REGION}" \
  --project "${GCP_PROJECT_ID}" \
  --format=json >"${service_json}"; then
  echo "::error::Cloud Run service '${CLOUD_RUN_SERVICE}' is missing. Complete Admin Terraform Stage 2 first; GitHub Actions only updates the image and does not create or own the service configuration."
  exit 1
fi

expected_service_account="boss-admin-api-dev@${GCP_PROJECT_ID}.iam.gserviceaccount.com"
if ! jq -e \
  --arg service_account "${expected_service_account}" \
  --arg secret "${ADMIN_DB_SECRET_NAME}" '
    .template.serviceAccount == $service_account
    and ([.template.containers[]?.env[]?
      | select(.name == "ADMIN_FIREBASE_PROJECT_ID"
        and (.value | type == "string" and length > 0))] | length == 1)
    and ([.template.containers[]?.env[]?
      | select(.name == "ADMIN_DB_URL"
        and (.value | test("^jdbc:postgresql:///boss\\?cloudSqlInstance=[^&]+:[^&]+:boss-dev-postgres&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC$"; "s")))] | length == 1)
    and ([.template.containers[]?.env[]?
      | select(.name == "ADMIN_DB_USER" and .value == "boss_admin")] | length == 1)
    and ([.template.containers[]?.env[]?
      | select(.name == "ADMIN_DB_PASSWORD_FILE" and .value == "/secrets/password")] | length == 1)
    and ([.template.volumes[]?
      | select(.name == "admin-db-password"
        and .secret.secret == $secret
        and any(.secret.items[]?; .path == "password"))] | length == 1)
    and ([.template.containers[]?.volumeMounts[]?
      | select(.name == "admin-db-password" and .mountPath == "/secrets")] | length == 1)
  ' "${service_json}" >/dev/null; then
  echo "::error::Cloud Run service configuration does not match the dedicated Platform Admin service account, Firebase project, database URL, DB user, or mounted secret. Reconcile it with infra/main.tf before deploying."
  exit 1
fi

if ! gcloud secrets versions list "${ADMIN_DB_SECRET_NAME}" \
  --project "${GCP_PROJECT_ID}" \
  --format=json | jq -e 'any(.[]; .state == "ENABLED")' >/dev/null; then
  echo "::error::Secret '${ADMIN_DB_SECRET_NAME}' has no enabled version. Add the approved admin database credential in Secret Manager before deploying."
  exit 1
fi

echo "Platform Admin Dev deployment prerequisites are present."
