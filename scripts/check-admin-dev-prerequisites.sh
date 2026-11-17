#!/usr/bin/env bash
set -euo pipefail

: "${GCP_PROJECT_ID:?GCP_PROJECT_ID must be set}"
: "${GCP_REGION:?GCP_REGION must be set}"
: "${ARTIFACT_REGISTRY_REPOSITORY:?ARTIFACT_REGISTRY_REPOSITORY must be set}"
: "${CLOUD_RUN_SERVICE:?CLOUD_RUN_SERVICE must be set}"
: "${ADMIN_DB_SECRET_NAME:?ADMIN_DB_SECRET_NAME must be set}"
: "${PLATFORM_ADMIN_FIREBASE_PROJECT_ID:?PLATFORM_ADMIN_FIREBASE_PROJECT_ID must be set}"

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
  --arg secret "${ADMIN_DB_SECRET_NAME}" \
  --arg firebase_project_id "${PLATFORM_ADMIN_FIREBASE_PROJECT_ID}" '
    (.spec.template.spec // .template) as $template
    | ($template.containers // []) as $containers
    | ([$containers[].env[]?
      | select(.name == "ADMIN_FIREBASE_PROJECT_ID"
        and .value == $firebase_project_id)]) as $firebase_project
    | ([$containers[].env[]?
      | select(.name == "ADMIN_DB_URL"
        and (.value | test("^jdbc:postgresql:///boss\\?cloudSqlInstance=[^&]+:[^&]+:boss-dev-postgres&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC$"; "s")))]) as $database_url
    | ([$containers[].env[]?
      | select(.name == "ADMIN_DB_USER" and .value == "boss_admin")]) as $database_user
    | ([$containers[].env[]?
      | select(.name == "ADMIN_DB_PASSWORD_FILE" and (.value | type == "string"))]) as $password_file
    | ($password_file[0].value // "") as $password_path
    | ($password_path | split("/") | .[:-1] | join("/")) as $mount_path
    | ([$template.volumes[]?
      | select((.secret.secretName // .secret.secret) == $secret
        and any(.secret.items[]?; .path == "password"))
      | .name]) as $secret_volumes
    | ($template.serviceAccountName // $template.serviceAccount) == $service_account
    and ($firebase_project | length) == 1
    and ($database_url | length) == 1
    and ($database_user | length) == 1
    and ($password_file | length) == 1
    and ($password_path | startswith("/") and endswith("/password"))
    and any($containers[]?; . as $container
      | any($container.env[]?; .name == "ADMIN_DB_PASSWORD_FILE" and .value == $password_path)
        and any($container.volumeMounts[]?; . as $mount
          | $mount.mountPath == $mount_path
            and ($secret_volumes | index($mount.name)) != null))
  ' "${service_json}" >/dev/null; then
  echo "::error::Cloud Run service configuration does not match the dedicated Platform Admin identity, Firebase, database, or mounted-secret requirements."
  exit 1
fi

if ! gcloud secrets versions list "${ADMIN_DB_SECRET_NAME}" \
  --project "${GCP_PROJECT_ID}" \
  --format=json | jq -e 'any(.[]; .state == "ENABLED")' >/dev/null; then
  echo "::error::Secret '${ADMIN_DB_SECRET_NAME}' has no enabled version. Add the approved admin database credential in Secret Manager before deploying."
  exit 1
fi

echo "Platform Admin Dev deployment prerequisites are present."
