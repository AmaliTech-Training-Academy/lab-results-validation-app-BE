## Summary

Makes the notification/email feature fully async and adds manual dismiss/batch-send controls for held notifications, fixes a live Postgres bug in the audit-log and ingestion-run date-range filters, corrects a misconfigured SharePoint reference filename, and switches reviewer resolution to match instructors by full name instead of email.

## What changed

**Notifications — fully async, dismiss, and per-run batch send**
- `POST /api/v1/notifications/{id}/send` no longer blocks the HTTP request thread on the SMTP round-trip. It now queues the send via `NotificationDispatchService.sendAsync` (`@Async("emailTaskExecutor")`) and returns `202 Accepted` immediately with the notification's pre-send state. Callers must poll `GET /{id}` for the eventual `SENT`/`FAILED` outcome — this is an API contract change from the previous synchronous `200` response.
- New `POST /api/v1/notifications/send-all?syncJobId=...` — sends every `PENDING`/`HELD` notification for one sync run at once. Returns `202` with the count queued; the actual sends run as a single async batch (log-and-continue per notification, same pattern as the existing `AUTO` auto-dispatch listener). `AUTO` notifications are unaffected since they already dispatch automatically at sync-run end.
- New `POST /api/v1/notifications/{id}/dismiss` — marks a `PENDING` notification `SKIPPED` (e.g. the digest is no longer relevant). Only `PENDING` can be dismissed; `SENT`/`FAILED`/`SKIPPED` are rejected with `422` rather than silently no-op'd, since dismissing an already-sent notification would misrepresent what happened.
- `notifications` table gains `dismissed_by`/`dismissed_at` (`V26__notification_dismiss.sql`), mirroring `ingestion_conflicts.resolved_by/resolved_at`. No status-constraint change needed — `SKIPPED` was already an allowed value, just never set by any code path until now.
- `NotificationResponse` now exposes `dismissedBy`/`dismissedAt`.

**Bug fix — Postgres "could not determine data type of parameter"**
- `AuditEventRepository.search` and `IngestionRunRepository.search` used `(:from IS NULL OR occurredAt >= :from)` for optional date-range filters. When the filter is omitted, Postgres has no typed context to resolve that bare `? IS NULL` placeholder against and fails at prepare time — reproduced live against the dev DB via `GET /api/v1/audit-log/audit-events?from=...`. Fixed by casting the null-check side explicitly: `CAST(:from AS timestamp) IS NULL OR ...`.

**Config fix — instructor reference file**
- `validata.sharepoint.ref-files.instructors` defaulted to `Quiz Reference.xlsx`, so Gate 3 was downloading whatever file was actually named "Quiz Reference.xlsx" and validating it against instructor columns (`name`/`email`/`specialization`), producing spurious `G3-MISSING-COLUMN` errors. Corrected to `Instructor Database.xlsx`, matching the existing `Trainee Database.xlsx` naming convention. Added a separate `validata.sharepoint.ref-files.quiz` property for the actual quiz reference file, which had been conflated with the instructors slot.

**Reviewer resolution — match by full name, not email**
- `ScoreRowValidationService` now resolves a score row's reviewer via `InstructorContactRepository.findByFullNameIgnoreCase` instead of `findByEmailIgnoreCase`, since the sheet's "Reviewer" column carries a name, not an email.
- `RejectionReasonSummary` gains a `description` field (`RejectionRuleDescriptions.describe(rule)`) that translates terse rule codes (e.g. `R5-UNKNOWN-REVIEWER`) into a plain-language explanation, since the code alone means nothing outside the dev team.

**New — instructor contact read API**
- `GET /api/v1/instructors` (paginated list) and `GET /api/v1/instructors/{id}` — read-only endpoints over `InstructorContact`, backed by a new `InstructorContactQueryService`.

## Out of scope / follow-ups

- The `send`/`send-all` API contract change (sync → async) means the frontend "Run-Review" screen needs to poll for status instead of reading it off the send response — worth confirming with the frontend team before this ships.
- No Testcontainers/real-Postgres integration test exists yet for `AuditEventRepository.search`/`IngestionRunRepository.search`; the date-range fix was verified by rebuilding against the live dev docker-compose stack, not by an automated test.

## Test plan

- [x] `mvn compile` — clean
- [x] Rebuilt and restarted the app against the live dev docker-compose stack (`docker compose build app && docker compose up -d app`); Flyway applied `V26` cleanly, app boots without error
- [x] Added `NotificationDispatchServiceTest` covering dismiss and batch-send
- [x] Updated `ScoreRowValidationServiceTest`, `CohortSyncControllerTest` for the full-name reviewer match and timestamp changes
- [ ] Live authenticated HTTP check of `/api/v1/notifications/send-all` and `/{id}/dismiss` — pending manual retest (blocked locally on not having valid admin credentials to mint a session)
