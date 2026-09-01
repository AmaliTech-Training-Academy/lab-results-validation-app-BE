# Lab Results Validation App — Backend (Validata)

Validata is a Spring Boot service that replaces a manual, spreadsheet-driven cohort grading
workflow with an automated pipeline: it pulls reference data and instructor score sheets
straight from SharePoint, validates them against a set of gated business rules, and syncs the
resulting grades — flagging conflicts and notifying instructors/admins instead of letting bad
data slide into production reports.

## Table of contents

- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [API overview](#api-overview)
- [Database migrations](#database-migrations)
- [Testing & quality gates](#testing--quality-gates)
- [CI/CD](#cicd)
- [Infrastructure](#infrastructure)
- [Project layout](#project-layout)
- [Further reading](#further-reading)

## What it does

A cohort's lifecycle moves through a series of validation **gates**, each of which must pass
before the next stage unlocks:

| Stage | Gate | Checks |
|---|---|---|
| **Stand-up** | Gate 1 | The SharePoint link resolves to a real, accessible drive item |
| | Gate 2 | The expected folder structure exists (`Reference Data/`, `Lab Scores/`, configurable) |
| | Gate 3 | The 5 reference workbooks (specializations, modules, labs, learners, instructors) parse and cross-validate against each other |
| **Reference Accept** | — | Admin reviews the parsed reference bundle and accepts it; reference data is then frozen (`REFERENCE_ACCEPTED`) |
| **Gate 4** | Gate 4 | Instructor score sheets are validated against the accepted reference data; a passing cohort moves to `STOOD_UP` |
| **Weekly sync** | — | Once stood up, score sheets are re-pulled from SharePoint on a schedule (or on demand), diffed, and any row that fails validation is raised as a **conflict** for an admin to resolve |

Cohort states: `DRAFT` → `REFERENCE_ACCEPTED` → `STOOD_UP` (→ `LOCKED`).

Long-running pipeline runs (stand-up, Gate 4, sync) execute asynchronously and stream their
progress to the client over **Server-Sent Events** so a UI can show gate-by-gate progress instead
of a single blocking spinner — see
[`docs/standup-sse-integration.md`](docs/standup-sse-integration.md).

Key product decisions baked into the design:

- A single `ADMIN` login role; instructors are passwordless `instructor_contacts` records, not
  application users.
- Reference workbooks are parsed locally with Apache POI rather than through the Graph Excel API,
  to avoid Excel session fragility.
- Reference data is frozen after acceptance — corrections require a discard/reset and re-run of
  the stand-up gates, not an in-app edit.
- Sync conflicts are resolved explicitly by an admin (`resolved_by`/`resolved_at`), and
  instructors/admins are notified via async email, with a manual dismiss/batch-send option for
  held notifications.

## Architecture

Feature-sliced by domain (not by technical layer at the root). Each domain package owns its own
`controller` / `service` / `repository` / `entity` / `dto` slice.

```
labresultsvalidator/src/main/java/com/amalitech/labresultsvalidator/
├── domain/
│   ├── auth/          # login, refresh/logout, password reset, JWT issuance
│   ├── user/           # user accounts
│   ├── cohort/         # cohort lifecycle (DRAFT → REFERENCE_ACCEPTED → STOOD_UP → LOCKED)
│   ├── standup/        # Gate 1-4 pipeline runners + SSE streaming controllers
│   ├── reference/      # parsed reference bundle (specializations, modules, labs, learners)
│   ├── instructor/     # instructor_contacts read API
│   ├── grading/        # score sheet ingestion/parsing + row-level grading rules
│   ├── sync/           # weekly/on-demand score sheet sync jobs, diffing, conflict resolution
│   ├── notification/   # async email dispatch, digests, dismiss/batch-send
│   ├── auditlog/       # ingestion run + audit event history
│   └── enums/
├── common/
│   ├── exceptions/      # GlobalExceptionHandler + domain exceptions
│   ├── response/        # ApiResponse envelope
│   ├── config/          # CORS, OpenAPI, async executors, etc.
│   ├── aop/              # cross-cutting aspects
│   ├── csv/
│   ├── service/          # e.g. EmailService
│   ├── validation/
│   └── utils/
├── infrastructure/
│   ├── graph/           # Microsoft Graph / SharePoint client (GraphDriveService, retry/backoff)
│   └── storage/         # S3 client (raw workbook archive for diffing/re-trigger)
├── security/            # SecurityConfig, JWT filters, password-reset filters
└── config/
```

Reference/score `.xlsx` files are also archived to S3 as they're pulled from SharePoint, so a
sync run can diff against the previous version without re-downloading it.

## Tech stack

- **Java 21**, **Spring Boot 4**
- **PostgreSQL** (via Spring Data JPA) with **Flyway** migrations
- **Redis** (caching / pending-bundle TTL storage)
- **Spring Security** + **JJWT** (access + refresh token auth)
- **Microsoft Graph SDK** + **Azure Identity** — SharePoint access (client-credentials flow)
- **Apache POI** — local `.xlsx` parsing, hardened against zip-bomb payloads
- **AWS SDK v2 (S3)** — workbook archive
- **OpenCSV**, **springdoc-openapi** (Swagger UI), **Lombok**, **Spring Mail**

## Getting started

### Prerequisites

- JDK 21
- Docker + Docker Compose (for Postgres/Redis, or the full stack)
- An Azure AD app registration with `Sites.Read.All` Graph API permission, if you need real
  SharePoint access (otherwise Gate 1-4 calls will fail against the Graph client)

### 1. Configure environment

```bash
cp .env.example .env
```

Fill in at minimum: `DB_PASSWORD`, `JWT_SECRET` (a default dev value ships in the template —
replace for anything beyond local use), and, if you're exercising the SharePoint integration,
`AZURE_TENANT_ID` / `AZURE_CLIENT_ID` / `AZURE_CLIENT_SECRET` / `SHAREPOINT_SITE_ID` plus AWS S3
credentials (see [Configuration](#configuration)).

### 2. Run the full stack with Docker Compose

```bash
docker compose up -d
```

This brings up Postgres, Redis, and the app (built from `labresultsvalidator/Dockerfile`),
wired together via `.env`. The app is exposed on `http://localhost:8080`.

### 3. Or run the app locally against Dockerized Postgres/Redis

```bash
docker compose up -d postgres redis
cd labresultsvalidator
./mvnw spring-boot:run
```

Flyway applies all migrations automatically on startup.

### 4. Explore the API

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health check: `http://localhost:8080/actuator/health`

## Configuration

All configuration lives in
[`labresultsvalidator/src/main/resources/application.properties`](labresultsvalidator/src/main/resources/application.properties)
as `${VAR}` placeholders sourced from `.env` (gitignored). Notable groups:

| Concern | Variables |
|---|---|
| Database | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` |
| Auth | `JWT_SECRET` |
| Redis | `REDIS_HOST`, `REDIS_PORT` |
| Mail (SMTP) | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` |
| URLs / CORS | `FRONTEND_URL`, `BASE_URL`, `CORS_ALLOWED_ORIGINS` |
| SharePoint / Graph | `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `SHAREPOINT_SITE_ID`, `SP_REF_FILE_*`, `SP_MAX_WORKBOOK_BYTES`, `SP_MAX_CONCURRENT_PREFETCH` |
| Graph retry/backoff | `GRAPH_RETRY_MAX_ATTEMPTS`, `GRAPH_RETRY_INITIAL_BACKOFF_MS`, `GRAPH_RETRY_MAX_BACKOFF_MS`, `GRAPH_RETRY_MAX_RETRY_AFTER_MS`, `GRAPH_RETRY_MAX_TOTAL_WAIT_MS` |
| AWS S3 (workbook archive) | `AWS_REGION`, `S3_BUCKET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` |
| Sync schedule | `SYNC_SCHEDULE_CRON` (default: Monday 08:00), `SYNC_SCHEDULE_ZONE`, `SYNC_SCHEDULER_POOL_SIZE` |
| Notifications | `NOTIF_FORMATS_PROVISIONAL` |
| Misc | `STANDUP_PENDING_TTL` (Gate 3 pending-bundle hold, seconds), `POI_MIN_INFLATE_RATIO`, `POI_MAX_ENTRY_BYTES` (zip-bomb hardening) |

See `.env.example` for the full list with defaults and comments.

## API overview

All endpoints are under `/api/v1`. Full request/response schemas are in Swagger UI; this is the
resource map:

| Resource | Base path | Purpose |
|---|---|---|
| Auth | `/api/v1/auth` | login, refresh, logout, forgot/reset/change password |
| Users | `/api/v1/users` | current user (`/me`), user lookup |
| Cohorts | `/api/v1/cohorts` | CRUD, SharePoint link, stand-up trigger, accept/discard reference, Gate 4, lock/unlock |
| Standup streams | `/api/v1/cohorts/{id}/standup/stream`, `/gate4/stream` | SSE progress for Gates 1-4 |
| Reference | `/api/v1/cohorts/{id}/reference` | accepted reference bundle |
| Instructors | `/api/v1/instructors` | read-only instructor contact list/detail |
| Sync | `/api/v1/cohorts/{id}/sync`, `/sync-schedules` | trigger/inspect sync runs, conflicts, resolve conflicts, recurring schedules, SSE stream |
| Notifications | `/api/v1/notifications` | list, send, batch-send, dismiss, settings, SSE stream |
| Audit log | `/api/v1/audit-log` | ingestion runs + audit events, filterable by date range |

## Database migrations

Schema is managed with Flyway (`labresultsvalidator/src/main/resources/db/migration`, `V1`…`Vn`).
Never edit a shipped migration — add a new `V<next>__description.sql` file instead.

## Testing & quality gates

```bash
cd labresultsvalidator
./mvnw clean verify
```

`verify` runs the full test suite (JUnit 5 + Mockito + Spring Test) with JaCoCo coverage
instrumentation, followed by Checkstyle (`checkstyle.xml`) — the same command CI runs.

## CI/CD

GitHub Actions (`.github/workflows/`):

- **`ci.yml`** — on every push to `main`/`develop`/`feature/**`/`feat/**`/`chore/**`/`hotfix/**`
  and PRs into `develop`:
  1. **Build & Test** — `mvn clean verify` (compile, test, Checkstyle, package), jar published as
     a shared artifact.
  2. **SonarQube Quality Gate** — spins up an ephemeral SonarQube, analyzes the prebuilt classes +
     coverage, fails the job if the gate isn't met.
  3. **Trivy Container Scan** — builds the runtime image from the prebuilt jar, fails on any
     CRITICAL/HIGH CVE.
  4. **TruffleHog Secret Scan** — fails on verified or unknown-status secrets in the diff.
- **`deploy-dev.yml`** — deploys to the dev environment (see [Infrastructure](#infrastructure)).

## Infrastructure

Terraform-managed AWS infrastructure lives in [`infra/`](infra/README.md) — reusable modules
(ECR, secrets/SSM, single-box EC2 dev stack, EventBridge schedule, CI/CD OIDC role) composed by
per-environment roots (`infra/dev`, with `infra/prod` planned). See `infra/README.md` for the
deploy flow and secret provisioning, and `docs/aws-architecture.drawio` for the diagram.

## Project layout

```
.
├── labresultsvalidator/   # the Spring Boot application (see its own README for local Postgres setup)
├── infra/                 # Terraform infra (modules + per-environment roots)
├── docs/                  # architecture diagrams, SSE integration guide
├── docker-compose.yml     # local Postgres + Redis + app stack
├── .env.example           # environment variable template
└── .github/workflows/     # CI + deploy pipelines
```

## Further reading

- [`labresultsvalidator/README.md`](labresultsvalidator/README.md) — local Postgres/Docker setup details
- [`docs/standup-sse-integration.md`](docs/standup-sse-integration.md) — SSE event contract for the stand-up/Gate 4 pipeline
- [`infra/README.md`](infra/README.md) — infrastructure, environments, and deploy flow
- [`Validata_PRD_v2_draft.md`](Validata_PRD_v2_draft.md) — product requirements driving this rebuild
