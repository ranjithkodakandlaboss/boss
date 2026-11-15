# BOSS Platform Admin API

Standalone Java 21 / Spring Boot service. It builds independently from `backend/` and serves the contract routes below `/admin/api/v1`. It has no Flyway runner and does not import `in.boss.shared.*` or customer service packages.

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

Deployment must create a separate `boss-admin-api` Cloud Run service and bind a distinct platform-admin Firebase project and restricted database principal. Do not deploy this image through the customer `boss-api` service. No cloud resources were provisioned as part of this change.

See `PLATFORM_ADMIN_INTEGRATION_REQUEST.md` for schema/contract integration items that block full provisioning and deployment readiness.
