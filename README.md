# dora-api

Spring Boot 3 / Java 21 backend for the DORA incident management platform.

## Prerequisites

- JDK 21 (Temurin recommended)
- Maven 3.9+ (or use the included `./mvnw` wrapper — no separate Maven install needed)
- Docker Desktop 4.x+ — required for Testcontainers (used in tests) and for the full local stack

## One-command quickstart

The API requires a running PostgreSQL instance. Two options:

**Option A — full stack via Docker Compose (recommended for first run):**
```bash
# From the workspace root (dora-workspace/)
cp .env.example .env
docker compose up
```
All five services start together. The API is available at `http://localhost:8080`.

**Option B — API only against a local Postgres:**
```bash
# Export env vars or rely on the defaults in application.yml
# Defaults: DB_URL=jdbc:postgresql://localhost:5432/dora, DB_USER=dora, DB_PASSWORD=dora
./mvnw spring-boot:run
```
Flyway will run `V1_0_0__baseline.sql` automatically on first start.

## Running tests

```bash
./mvnw verify
```

Testcontainers pulls `postgres:15-alpine` and wires it into the Spring context automatically via `@DynamicPropertySource`. Docker must be running. No manual database setup is required.

## API endpoints

| Endpoint | Description |
|---|---|
| `GET /api/v1/health` | Application health — returns `{ status, version, timestamp }` |
| `GET /actuator/health` | Spring Boot Actuator health (includes DB connectivity check) |
| `GET /actuator/info` | Build info |
| `GET /swagger-ui.html` | Swagger UI — loads `/openapi.yaml` as the contract |
| `GET /v3/api-docs` | springdoc-generated API docs (supplementary) |

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Environment variables

All environment-specific values are read from env vars with safe local defaults defined in `application.yml`. See `dora-workspace/.env.example` for the full list. No secret values are hardcoded in source.

Key variables:
| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/dora` | JDBC URL |
| `DB_USER` | `dora` | DB username |
| `DB_PASSWORD` | `dora` | DB password |
| `API_PORT` | `8080` | HTTP port |
| `S3_ENDPOINT` | `http://localhost:9000` | MinIO (local S3 substitute) |
| `SES_HOST` | `localhost` | MailHog (local SES substitute) |

## Docker build

```bash
docker build -t dora-api .
docker run -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/dora \
  -e DB_USER=dora \
  -e DB_PASSWORD=dora \
  dora-api
```

The Dockerfile uses a multi-stage build: Maven + JDK 21 for compilation, JRE 21 Alpine for the runtime image. The runtime container runs as a non-root `dora` user.

## Spec

See [LLD-01: Local Dev Baseline](../dora-docs/low-level-design/LLD-01-local-dev-baseline.md) for acceptance criteria, architecture decisions, and the full backend spec (§4).
