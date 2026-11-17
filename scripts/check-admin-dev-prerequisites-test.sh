#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATE="${ROOT}/scripts/check-admin-dev-prerequisites.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat >"${TMP}/gcloud" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail

case "$*" in
  *"artifacts repositories describe"*)
    printf '%s\n' "projects/boss-dev-514813/locations/asia-south1/repositories/boss"
    ;;
  *"run services describe"*)
    cat "${ADMIN_SERVICE_JSON}"
    ;;
  *"secrets versions list"*)
    printf '%s\n' '[{"state":"ENABLED"}]'
    ;;
  *)
    echo "Unexpected gcloud invocation" >&2
    exit 64
    ;;
esac
MOCK
chmod +x "${TMP}/gcloud"

cat >"${TMP}/service.json" <<'JSON'
{
  "kind": "Service",
  "apiVersion": "serving.knative.dev/v1",
  "spec": {
    "template": {
      "spec": {
        "serviceAccountName": "boss-admin-api-dev@boss-dev-514813.iam.gserviceaccount.com",
        "containers": [
          {
            "env": [
              {"name": "ADMIN_FIREBASE_PROJECT_ID", "value": "boss-f7492"},
              {"name": "ADMIN_DB_URL", "value": ""},
              {"name": "ADMIN_DB_USER", "value": "boss_admin"},
              {"name": "ADMIN_DB_PASSWORD_FILE", "value": "/secrets/db/password"}
            ],
            "volumeMounts": [
              {"name": "admin-db-password", "mountPath": "/secrets/db"}
            ]
          }
        ],
        "volumes": [
          {
            "name": "admin-db-password",
            "secret": {
              "secretName": "boss-admin-db-password-dev",
              "items": [{"key": "latest", "path": "password"}]
            }
          }
        ]
      }
    }
  }
}
JSON

admin_db_url="$(printf '%s' \
  'jdbc:' \
  'postgresql:///boss?cloudSqlInstance=project:region:boss-dev-postgres&socketFactory=com.google.cloud.sql.postgres.SocketFactory&ipTypes=PUBLIC')"
jq --arg db_url "${admin_db_url}" \
  '(.spec.template.spec.containers[0].env[]
    | select(.name == "ADMIN_DB_URL").value) = $db_url' \
  "${TMP}/service.json" >"${TMP}/service-with-db-url.json"
mv "${TMP}/service-with-db-url.json" "${TMP}/service.json"

export PATH="${TMP}:${PATH}"
export ADMIN_SERVICE_JSON="${TMP}/service.json"
export GCP_PROJECT_ID="boss-dev-514813"
export GCP_REGION="asia-south1"
export ARTIFACT_REGISTRY_REPOSITORY="boss"
export CLOUD_RUN_SERVICE="boss-admin-api"
export ADMIN_DB_SECRET_NAME="boss-admin-db-password-dev"
export PLATFORM_ADMIN_FIREBASE_PROJECT_ID="boss-f7492"

output_file="${TMP}/output.txt"
if ! "${GATE}" >"${output_file}" 2>&1; then
  cat "${output_file}" >&2
  echo "Admin prerequisite guard rejected a valid Cloud Run v1 service" >&2
  exit 1
fi

grep -Fq "Platform Admin Dev deployment prerequisites are present." "${output_file}"

assert_rejected() {
  local service_json="$1"
  ADMIN_SERVICE_JSON="${service_json}" "${GATE}" >"${output_file}" 2>&1 && {
    echo "Admin prerequisite guard accepted invalid service configuration" >&2
    exit 1
  }
  grep -Fq "Cloud Run service configuration does not match" "${output_file}"
}

jq '.spec.template.spec.serviceAccountName = "unexpected"' \
  "${TMP}/service.json" >"${TMP}/wrong-service-account.json"
jq '(.spec.template.spec.containers[0].env[]
  | select(.name == "ADMIN_FIREBASE_PROJECT_ID").value) = "unexpected"' \
  "${TMP}/service.json" >"${TMP}/wrong-firebase-project.json"
jq '.spec.template.spec.volumes[0].secret.secretName = "unexpected"' \
  "${TMP}/service.json" >"${TMP}/wrong-secret.json"
jq '.spec.template.spec.containers[0].volumeMounts[0].mountPath = "/wrong"' \
  "${TMP}/service.json" >"${TMP}/wrong-mount.json"

assert_rejected "${TMP}/wrong-service-account.json"
assert_rejected "${TMP}/wrong-firebase-project.json"
assert_rejected "${TMP}/wrong-secret.json"
assert_rejected "${TMP}/wrong-mount.json"

echo "Admin deployment prerequisite regression tests passed"
