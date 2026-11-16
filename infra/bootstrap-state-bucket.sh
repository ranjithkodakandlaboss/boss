#!/usr/bin/env bash
# bootstrap-state-bucket.sh
#
# Creates and configures the GCS bucket for OpenTofu remote state.
# Run ONCE by an operator with:
#   - roles/storage.admin on project boss-dev-514813
#   - roles/iam.securityAdmin (to set bucket IAM)
#
# This script is idempotent: re-running it on an existing bucket is safe.
#
# Prerequisites: gcloud auth login && gcloud config set project boss-dev-514813

set -euo pipefail

PROJECT_ID="boss-dev-514813"
BUCKET="boss-tofu-state"
REGION="asia-south1"

# CI/CD service account that runs `tofu apply` in GitHub Actions (WIF)
# Adjust if the SA name differs in your project
CICD_SA="github-actions@${PROJECT_ID}.iam.gserviceaccount.com"

echo "==> Creating GCS bucket gs://${BUCKET} in ${REGION}..."
# -b on  = uniform bucket-level access (no per-object ACLs)
gsutil mb \
  -p "${PROJECT_ID}" \
  -l "${REGION}" \
  -b on \
  "gs://${BUCKET}" || echo "Bucket already exists — continuing."

echo "==> Enabling object versioning for state recovery..."
gsutil versioning set on "gs://${BUCKET}"

echo "==> Restricting bucket IAM to authorized principals only..."
# Remove allUsers and allAuthenticatedUsers if present
gsutil iam ch -d allUsers "gs://${BUCKET}" 2>/dev/null || true
gsutil iam ch -d allAuthenticatedUsers "gs://${BUCKET}" 2>/dev/null || true

# Grant objectAdmin to the CI/CD service account
gsutil iam ch "serviceAccount:${CICD_SA}:roles/storage.objectAdmin" "gs://${BUCKET}"

echo "==> Bucket IAM policy:"
gsutil iam get "gs://${BUCKET}"

echo ""
echo "==> Bootstrap complete."
echo "    Run the following from infra/ to initialise OpenTofu:"
echo ""
echo "    cd infra"
echo "    tofu init \\"
echo "      -backend-config=\"bucket=${BUCKET}\" \\"
echo "      -backend-config=\"prefix=terraform/state\""
echo ""
echo "    If you have existing local state to migrate:"
echo "    tofu init -migrate-state"
