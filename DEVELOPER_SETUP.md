# BOSS — Developer Workstation Setup Guide

**Version:** v1.0 | **Last updated:** 15 November 2026

---

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Java (Temurin) | 21 | `sdk install java 21-tem` (sdkman) or https://adoptium.net |
| Maven | 3.9+ | `sdk install maven` or https://maven.apache.org |
| Node.js | 22 LTS | https://nodejs.org or `nvm install 22` |
| Docker Desktop | Latest | https://docker.com |
| IntelliJ IDEA | 2024.3+ | Community edition is sufficient |
| Git | 2.40+ | `brew install git` / `apt install git` |

---

## 1. Clone the repository

```bash
git clone https://github.com/ranjithkodakandlaboss/boss.git
cd boss
git checkout -b feature/your-story-name
```

---

## 2. Start the local database

```bash
cd docker
docker compose up -d postgres
```

PostgreSQL 15 starts on **localhost:5432**. Connection details:
- Database: `boss`
- Username: `boss`
- Password: `boss`

Verify:
```bash
docker compose ps
```

---

## 3. Run Flyway migrations

Migrations run automatically on Spring Boot startup. To run them standalone:

```bash
cd backend
mvn flyway:migrate -Dspring.datasource.url=jdbc:postgresql://localhost:5432/boss \
                   -Dspring.datasource.username=boss \
                   -Dspring.datasource.password=boss
```

---

## 4. Build and run the backend

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The API starts on **http://localhost:8080**. Health check: http://localhost:8080/actuator/health

---

## 5. Run tests

```bash
cd backend
mvn test                 # unit tests only (fast)
mvn verify               # unit + integration tests (requires Docker for Testcontainers)
```

Integration tests use Testcontainers — Docker must be running. No H2 anywhere.

---

## 6. Run the frontend (once scaffolded)

```bash
cd frontend
npm install
npm run dev
```

Frontend starts on **http://localhost:5173**.

---

## 7. Environment variables for local development

No secrets in environment variables. For local development, the application uses:
- `application-local.yml` defaults (pre-configured for `localhost:5432`)
- Firebase credential: not required for backend unit/integration tests

For end-to-end local testing with Firebase, place your Firebase service account JSON at a path and set:
```bash
export BOSS_FIREBASE_CREDENTIAL_PATH=/path/to/service-account.json
```

**Never commit any `*.json` credential files to the repository.**

---

## 8. IntelliJ setup

1. Open the `backend/` directory as a Maven project
2. Set Project SDK to Java 21
3. Enable annotation processing (for Lombok)
4. Run configuration: Spring Boot `BossApplication`, active profile `local`

---

## 9. Git branch convention

| Owner | Branch prefix |
|---|---|
| Ranjith | `feature/vs-01-*`, `feature/vs-02-*`, `feature/vs-03-*`, `feature/vs-05-*` etc |
| Saad | `feature/vs-04-*`, `feature/vs-06-*`, `feature/vs-08-*` etc |
| Agent sessions | `claude/*` (auto-created by Claude Code) |

Every PR must reference its GitHub issue: `Closes #N` in the PR description.

---

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| `Connection refused :5432` | Run `docker compose up -d postgres` from `docker/` |
| `Flyway checksum mismatch` | Never edit a committed migration. Create a new V{NNN} |
| `Testcontainers: cannot connect to Docker` | Ensure Docker Desktop is running |
| `FirebaseApp not initialised` | Set `BOSS_FIREBASE_CREDENTIAL_PATH` or run with mock profile |
| Port 5432 already in use | Stop local PostgreSQL: `brew services stop postgresql` |
