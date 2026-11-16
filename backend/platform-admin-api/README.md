# BOSS Platform Admin API

Standalone Java 21 / Spring Boot service. It builds independently from `backend/` and serves the implemented issue-scope routes below `/admin/api/v1`. It has no Flyway runner and does not import `in.boss.shared.*` or customer service packages. The main backend migration set must be applied first; V067 creates the onboarding table used by tenant provisioning.

`GET /tenants` lists control-plane rows. The `boss_admin` login needs the SELECT policies in `src/main/resources/db/boss-admin-grants.sql`. Apply that script as a superuser before startup. It is not a Flyway migration.

Provisioning calls `seed_tenant_defaults` in the same transaction, checks the rows it wrote, and leaves the tenant in `provisioning` until `POST /tenants/{id}/activate`. Activation requires that seed to be present. When `adminEmail` is supplied, activation also requires a real initial-owner invitation. The admin service does not mint that token. Until the customer identity contract can create an OWNER invitation in the customer Firebase project, provisioning with an email returns HTTP 424 and no token. Retry with `POST /tenants/{id}/admin-invite`.

GSTIN, financial year, stores, and the company logo are customer onboarding data. They are not part of the provisioning request.

## Local run

Use Java 21 and Maven. Configure a local PostgreSQL instance with the existing BOSS schema, a narrowly privileged `ADMIN_DB_USER`, and an admin Firebase project. The database password is read from a Secret Manager mounted file at `ADMIN_DB_PASSWORD_FILE` (default `/secrets/admin-db-password`). Firebase credentials come from Application Default Credentials or the Cloud Run runtime identity; do not use or commit service-account JSON files.

```sh
cd backend/platform-admin-api
ADMIN_DB_URL=jdbc:postgresql://localhost:5432/boss \
ADMIN_DB_USER=boss_admin \
ADMIN_FIREBASE_PROJECT_ID=your-admin-project \
mvn spring-boot:run
```

The `test` profile replaces Firebase verification with a test double. Integration tests use PostgreSQL 15 through Testcontainers and require Docker.

## Build and container

```sh
mvn -f backend/platform-admin-api/pom.xml package
docker build -f backend/platform-admin-api/Dockerfile backend/platform-admin-api
```

`infra/main.tf` defines the separate `boss-admin-api` Cloud Run service and service account, mounts its own Secret Manager database password, and binds `ADMIN_FIREBASE_PROJECT_ID` to the platform-admin Firebase project. OpenTofu validates that this project ID differs from `customer_firebase_project_id`. The Admin-only configuration intentionally reuses the live Cloud SQL instance `boss-dev-postgres` and database `boss`; it does not create a second SQL instance, VPC, or customer-facing networking stack.

Before applying the infrastructure, provide a secret version for `boss-admin-db-password-<env>`, create the restricted `boss_admin` PostgreSQL login and grants described in `PLATFORM_ADMIN_INTEGRATION_REQUEST.md`, and set both Firebase project IDs. No cloud resources were provisioned or applied as part of this change.

See `PLATFORM_ADMIN_INTEGRATION_REQUEST.md` for schema/contract integration items that block full provisioning and deployment readiness.

## Dev deployment

`.github/workflows/ci.yml` provides a separate `deploy-admin-dev` job. It runs only for a manual workflow dispatch on `main`, after every existing quality gate succeeds. The job builds the Platform Admin image from this module's Dockerfile, pushes it to `asia-south1-docker.pkg.dev/boss-dev-514813/boss/boss-admin-api:<commit-sha>`, and updates the existing `boss-admin-api` Cloud Run service. It changes only the image; the service account, Admin Firebase project setting, and database secret mount remain owned by OpenTofu. A preflight script checks those settings and an enabled database-secret version before the image is built or pushed.

Before dispatching:

- Configure the GitHub Actions repository variables `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT`. Bind the GitHub WIF principal to the deployment service account with `roles/iam.workloadIdentityUser`; grant that service account `roles/artifactregistry.writer` on repository `boss`, `roles/run.admin` in the Dev project, and `roles/iam.serviceAccountUser` on `boss-admin-api-dev@boss-dev-514813.iam.gserviceaccount.com`. Grant `roles/secretmanager.viewer` only on the `boss-admin-db-password-dev` secret so the preflight can call `secretmanager.versions.list` and inspect enabled-version metadata. This is metadata-only access: do not grant Secret Manager Admin or `secretmanager.versions.access` / payload access to the deployment identity.
- Apply the Dev OpenTofu configuration in project `boss-dev-514813` and region `asia-south1` so the `boss` Artifact Registry repository, `boss-admin-api` Cloud Run service, dedicated `boss-admin-api-dev` service account, and `boss-admin-db-password-dev` secret exist with their runtime IAM bindings. The runtime reuses the existing `boss-dev-postgres` instance and public DB endpoint; it does not create a VPC connector or private-network stack.
- Set the real `admin_firebase_project_id` in the OpenTofu Dev configuration to an existing Firebase project, distinct from the customer Firebase project, with Firebase Authentication configured. Never put project IDs or credentials in the workflow source.
- Add an enabled version of the approved admin database credential to `boss-admin-db-password-dev`; create the restricted `boss_admin` database role and apply `src/main/resources/db/boss-admin-grants.sql` after the BOSS schema migrations. The workflow does not create database credentials, inspect secret contents, or apply SQL.

The preflight stops with an explicit error if the Artifact Registry repository, Cloud Run service, required service configuration, or enabled secret version is missing. It lists Secret Manager version metadata only; it never reads or accesses the database secret payload. It cannot verify the database role/grants or Firebase project's readiness; those must be confirmed by the operator. Do not close Issue #24 until the actual Cloud Run deployment, service health check, and database connectivity check have succeeded.

### Image and service naming

The Platform Admin paths are aligned: Cloud Build's `_ADMIN_IMAGE`, the new GitHub Actions job, and OpenTofu all use repository `boss` and image name `boss-admin-api`; the workflow and OpenTofu both target service `boss-admin-api`. Cloud Build currently tags the image `cloudbuild-latest`, OpenTofu defaults `admin_image_tag` to `latest`, and GitHub Actions uses the immutable commit SHA tag.

The pre-existing customer path differs: GitHub Actions and Cloud Build use Artifact Registry repository `boss-docker`, while OpenTofu defines repository `boss` and constructs the `boss-api` image path there. The customer deployment job is intentionally unchanged by this issue; changing that legacy path requires a separate, explicitly scoped migration. The workflow's `boss-api-dev` service name also differs from the OpenTofu `boss-api` service name. Neither customer discrepancy is reused for or silently changed by the Platform Admin deployment.
