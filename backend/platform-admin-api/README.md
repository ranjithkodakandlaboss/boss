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

`infra/main.tf` defines a two-stage Admin-only provisioning path. Stage 1 creates the Admin Artifact Registry repository, service account, secret container, and runtime IAM without querying Cloud SQL or creating Cloud Run. Stage 2 creates the separate `boss-admin-api` service after the initial image and operator prerequisites are ready. The service mounts its own Secret Manager database password and binds `ADMIN_FIREBASE_PROJECT_ID` to a Firebase project distinct from `customer_firebase_project_id`. Both stages reuse the live Cloud SQL instance `boss-dev-postgres` and database `boss`; neither creates a second SQL instance, VPC, or customer-facing networking stack.

No cloud resources were provisioned or applied as part of this change.

See `PLATFORM_ADMIN_INTEGRATION_REQUEST.md` for schema/contract integration items that block full provisioning and deployment readiness.

## Dev provisioning and deployment

The Admin infrastructure uses two explicit OpenTofu stages. The remote state must be approved and available before either stage; the current real remote plan is blocked by the missing backend noted below. Do not initialize against or migrate customer state.

### Stage 1 — bootstrap prerequisites

With the approved Dev variable file and backend, run the Admin-only root with `admin_service_enabled=false` (the default). This stage creates the `boss` Artifact Registry repository, dedicated `boss-admin-api-dev` service account, `boss-admin-db-password-dev` secret container, and its runtime Secret Manager / Cloud SQL IAM bindings. It does not read Cloud SQL, create the Cloud Run service, or require Admin Firebase configuration. The secret container has no credential version until the operator supplies one.

After Stage 1, the operator must:

1. Add the approved Admin DB credential as an enabled version of `boss-admin-db-password-dev`.
2. Set up a real Admin Firebase project with Authentication enabled, distinct from the customer Firebase project, and record its project ID for Stage 2.
3. Have the DBA create the restricted `boss_admin` login and apply `src/main/resources/db/boss-admin-grants.sql` after the BOSS schema migrations.
4. Ensure the Cloud Build service account can write to Artifact Registry repository `boss`.

These are operator/DBA actions, not workflow actions; this repository does not create credentials, modify Firebase, or execute SQL.

### Build and push the initial image

Before Stage 2, build and push the Admin image to the repository created in Stage 1:

```sh
cd src/frontend
npm ci
npm test
npm run build
cd ../..
mvn -f backend/platform-admin-api/pom.xml clean package -DskipTests

gcloud builds submit --project boss-dev-514813 --config cloudbuild-admin.yaml .
```

The frontend build writes the Platform Admin UI into
`backend/platform-admin-api/src/main/resources/static`, which is packaged into the
API JAR and Docker image. Build the frontend before packaging the backend; otherwise
the image can contain stale or missing UI assets. This Admin-only Cloud Build config
builds and pushes
`asia-south1-docker.pkg.dev/boss-dev-514813/boss/boss-admin-api:bootstrap-v1`.
The same explicit `bootstrap-v1` tag is the OpenTofu `admin_image_tag` default and
the `_ADMIN_IMAGE` tag in `cloudbuild.yaml`; it is not an implicit `latest` tag.
The initial service cannot be created until this image exists.

### Stage 2 — create the Terraform-owned Cloud Run service

After the enabled secret version, Firebase project, DBA role/grants, and bootstrap image are ready, the operator enables the service in the same Admin-only state and applies with:

```sh
tofu -chdir=infra apply \
  -var-file=terraform.tfvars \
  -var='admin_service_enabled=true' \
  -var='admin_image_tag=bootstrap-v1'
```

Stage 2 creates `boss-admin-api` with the dedicated service account, Admin Firebase project, existing Cloud SQL URL (`boss` database on `boss-dev-postgres` via the Cloud SQL Socket Factory), and mounted `boss-admin-db-password-dev` secret. The service uses the pushed bootstrap image. Keep `admin_service_enabled=true` in subsequent OpenTofu runs.

### Subsequent GitHub Actions image updates

`.github/workflows/ci.yml` provides a separate `deploy-admin-dev` job. It runs only on a manual dispatch from `main` after every quality gate succeeds. The job checks the Stage 2 service configuration and an enabled secret version, builds and pushes an immutable commit-SHA image, then invokes `gcloud run services update --image ...` for `boss-admin-api`. That command changes only the image; it does not create the service or pass environment, identity, scaling, or secret configuration. OpenTofu also ignores changes to the service image after initial creation, so a later Terraform apply preserves GitHub's image rollout while retaining Terraform ownership of service configuration.

The workflow intentionally requires the service to exist. The safe first-deployment alternative is the explicit Stage 1 → operator prerequisites → initial image push → Stage 2 sequence above, which creates the configured service with OpenTofu before GitHub Actions is dispatched. Do not dispatch the workflow as a substitute for Terraform service creation.

Before a future, explicitly authorized workflow dispatch, configure the GitHub Actions repository variables `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT`. Bind the GitHub WIF principal to the deployment identity with `roles/iam.workloadIdentityUser`; grant it `roles/artifactregistry.writer` on repository `boss`, `roles/run.admin` in the Dev project, and `roles/iam.serviceAccountUser` on `boss-admin-api-dev@boss-dev-514813.iam.gserviceaccount.com`. Grant `roles/secretmanager.viewer` only on `boss-admin-db-password-dev` so preflight can inspect enabled-version metadata. Do not grant Secret Manager Admin or `secretmanager.versions.access` / payload access to the deployment identity.

### State ownership and remote plan gate

This repository keeps the Admin-only Terraform root isolated from any customer-owned state by using the GCS backend key `boss-tofu-state` + `terraform/state/platform-admin/dev`. The bucket is shared only if the operator approves it; the state key is not the legacy `terraform/state` prefix used by the customer stack. Do not create a replacement bucket, move customer state, or change the customer Terraform ownership. The state key is a separate Admin-only ownership boundary and must be created/approved by the platform operator before the first real `tofu init` and `tofu plan`.

The explicit requirements are:

- GCS bucket: `boss-tofu-state`
- Admin state prefix: `terraform/state/platform-admin/dev`
- Ownership: Admin-only Terraform root, separate from customer service state
- Resource scope: only `boss-admin-api`, its dedicated service account, secret, IAM bindings, and monitoring resources
- Existing data source: Cloud SQL instance `boss-dev-postgres` only; no SQL instance creation or database mutation

If the bucket or state path does not exist yet, the operator must create or restore it before any non-dry-run plan. Until that is done, the correct status is "real remote plan blocked by missing approved backend" rather than "empty state".

The preflight stops with an explicit error if the Artifact Registry repository, Stage 2 Cloud Run service, required service configuration, or enabled secret version is missing. It lists Secret Manager version metadata only; it never reads or accesses the database secret payload. It cannot verify the database role/grants or Firebase project's readiness; those must be confirmed by the operator. Do not close Issue #24 until the actual Cloud Run deployment, service health check, and database connectivity check have succeeded.

### Image and service naming

The Platform Admin paths are aligned: Cloud Build, the GitHub Actions job, and OpenTofu all use repository `boss` and image name `boss-admin-api`; the workflow and OpenTofu both target service `boss-admin-api`. The initial image uses `bootstrap-v1`; GitHub Actions uses immutable commit-SHA tags for subsequent releases.

The pre-existing customer path differs: GitHub Actions and Cloud Build use Artifact Registry repository `boss-docker`, while OpenTofu defines repository `boss` and constructs the `boss-api` image path there. The customer deployment job is intentionally unchanged by this issue; changing that legacy path requires a separate, explicitly scoped migration. The workflow's `boss-api-dev` service name also differs from the OpenTofu `boss-api` service name. Neither customer discrepancy is reused for or silently changed by the Platform Admin deployment.
