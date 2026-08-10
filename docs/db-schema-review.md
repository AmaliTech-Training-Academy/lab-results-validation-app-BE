# DB Schema Review — Normalization (3NF) & Optimization

> **Update:** every finding below is now resolved. §2, §3, 1.6, and the
> `learners` uniqueness scope in 1.7 landed in `V30__schema_review_fixes.sql`.
> 1.1, 1.2, 1.3, and 1.4 landed in `V31__cohort_consistency_guards.sql`, via
> triggers/a `CHECK` rather than the bigger column-removal refactor — each
> redundant/denormalized column is kept (removing it would mean rewriting
> live queries/entities that depend on it), but the DB now actively rejects
> any write where the redundant copy disagrees with its source of truth.
> Every migration was verified against the actual app code first and tested
> end-to-end against a disposable Postgres container (see the note at the end
> of each section). Two items were corrected after checking the code —
> `nsp_name` turned out to be actively used (not dead), and the `learners`
> uniqueness issue turned out to be a confirmed live bug, not a hypothetical.
> The only thing genuinely left on the table is the *bigger* refactor variant
> of 1.1/1.2/1.3 (dropping the redundant columns outright and rewriting the
> dependent queries) — not required, since the triggers close the actual gap,
> but available later if the redundancy itself becomes worth removing.

**Scope:** Reconstructed final schema state from `labresultsvalidator/src/main/resources/db/migration/V1__create_tables.sql` through `V29__instructor_specialization_assignments.sql`. 19 tables, PostgreSQL, Flyway-versioned.

**Verdict up front:** the schema is close to 3NF and mostly well-indexed. It is *not* fully 3NF — there are a handful of stored-but-derivable columns (transitive dependencies) and one vestigial column left over from a reverted migration. None of these are correctness-breaking today because nothing in the app currently writes conflicting values, but none of them are enforced by the database either, so they're one bad write away from silently drifting. There's also a real (not cosmetic) referential-integrity gap: several of the newer "job" tables dropped the `REFERENCES users(id)` FK that the original V1 tables all have.

Findings are ranked by real-world impact, not by how "textbook" the violation is.

---

## 1. Normalization issues (3NF and adjacent)

### 1.1 `learners.cohort_id` is redundant with `learners.specialization_id` — HIGH
```sql
learners (
    ...
    cohort_id         UUID NOT NULL REFERENCES cohorts(id),
    specialization_id UUID NOT NULL REFERENCES specializations(id),
    ...
)
specializations (
    cohort_id UUID NOT NULL REFERENCES cohorts(id),
    ...
)
```
`specialization_id` already determines `cohort_id` via `specializations.cohort_id`. Storing both means the same fact ("which cohort is this learner in") is represented twice, and **nothing in the schema keeps them in sync** — there's no `CHECK`, no trigger, no composite FK tying `learners.cohort_id` to `specializations.cohort_id`. If a learner row is ever written with a `specialization_id` from a *different* cohort than its `cohort_id`, the row inserts cleanly and every downstream query that joins through `specialization_id` vs. the ones that filter on `cohort_id` directly will quietly disagree.

**Fix:** either drop `learners.cohort_id` and derive it via `specialization_id → specializations.cohort_id` in queries/views, or — if the direct column is kept for index/query-performance reasons — add a trigger (mirroring the existing `set_updated_at` pattern) that validates `cohort_id = (SELECT cohort_id FROM specializations WHERE id = NEW.specialization_id)` on insert/update.

**Status: FIXED (`V31`), trigger approach.** `learners.cohort_id` is read directly by `LearnerRepository.findAllByCohortId`/`existsByLearnerIdAndCohortId` and indexed (`idx_learners_cohort`) — dropping the column would mean rewriting those call sites to join through `specialization_id`, a bigger change than the actual complaint warranted. Went with the trigger fallback instead: `trg_learners_cohort_consistency` raises on insert/update if `cohort_id` disagrees with `specializations.cohort_id` for the row's `specialization_id`. Verified: a learner row consistent with its specialization's cohort inserts fine; one deliberately mismatched is rejected with a clear error naming both values.

### 1.2 `ingestion_conflicts.cohort_id` is 100% derivable — MEDIUM
```sql
ingestion_conflicts (
    ingestion_run_id UUID NOT NULL REFERENCES ingestion_runs(id),
    cohort_id        UUID NOT NULL REFERENCES cohorts(id),
    ...
)
```
`ingestion_run_id` is `NOT NULL`, and `ingestion_runs.cohort_id` already exists — so `cohort_id` here is a pure transitive dependency with no legitimate independent case (unlike `notifications.cohort_id`, see 1.3, where the source column is nullable). This is denormalized purely for the `idx_conflicts_cohort_status` index/query convenience, which is a reasonable trade-off *if* it's documented as such — right now it just reads as an oversight.

**Fix:** either keep it and add a comment explicitly stating it's a denormalized read-path column that must always equal `ingestion_runs.cohort_id`, or drop it and index `ingestion_runs(cohort_id)` + join.

**Status: FIXED (`V31`), trigger approach.** Same reasoning as 1.1 — kept the column (it's what `idx_conflicts_cohort_status` is built on), added `trg_conflicts_cohort_consistency` to reject any insert/update where `cohort_id` disagrees with `ingestion_runs.cohort_id` for the row's `ingestion_run_id`. Verified both directions.

### 1.3 `notifications.cohort_id` / `sync_job_id` / `ingestion_run_id` triple redundancy — LOW/MEDIUM
`notifications` carries `ingestion_run_id`, `sync_job_id`, and `cohort_id` simultaneously. `cohort_id` is derivable from either of the other two when they're populated, but both are nullable (a notification can exist without a run or a sync job), so `cohort_id` does carry independent information in that case. Not a clean violation, but worth flagging: there are now three different paths to "which cohort is this about," and nothing guarantees they agree when more than one is populated.

**Fix:** document the precedence/consistency rule, or add a `CHECK` that when `ingestion_run_id` is set, `cohort_id` must match (harder to express as a plain `CHECK`; a trigger is the realistic option).

**Status: FIXED (`V31`), trigger approach.** `trg_notifications_cohort_consistency` checks both paths independently: if `ingestion_run_id` is set, `cohort_id` must match `ingestion_runs.cohort_id`; if `sync_job_id` is set, it must match `cohort_sync_jobs.cohort_id`. A notification with neither set is untouched (its `cohort_id` is then the only source of truth, which is legitimate). Verified: consistent inserts on both paths succeed, a mismatch on either path is rejected, and a notification with only `cohort_id` set still inserts fine.

### 1.4 `notifications` recipient columns — unenforced polymorphic FK — MEDIUM
```sql
recipient_kind          VARCHAR(20) NOT NULL CHECK (recipient_kind IN ('instructor','admin')),
recipient_instructor_id UUID REFERENCES instructor_contacts(id),
recipient_user_id       UUID REFERENCES users(id),
```
This is a polymorphic association (`recipient_kind` selects which FK column is "live"), but there's no constraint enforcing the exclusivity/pairing — a row with `recipient_kind = 'instructor'` and `recipient_instructor_id = NULL` (or with *both* FKs populated) is perfectly legal at the DB level.

**Fix:** add
```sql
CONSTRAINT chk_notif_recipient CHECK (
    (recipient_kind = 'instructor' AND recipient_instructor_id IS NOT NULL AND recipient_user_id IS NULL) OR
    (recipient_kind = 'admin'      AND recipient_user_id       IS NOT NULL AND recipient_instructor_id IS NULL)
)
```

**Status: FIXED (`V31`).** Checked every write site (`NotificationStagingService`, `NotificationAlertService`) — all four already pair `recipient_kind` with exactly one correct ID, so this constraint changes nothing live; it just forecloses the two invalid shapes going forward. Verified: a valid `instructor`/`recipient_instructor_id` pairing inserts fine; `recipient_kind='instructor'` with a null `recipient_instructor_id`, and a row with both IDs populated, are both correctly rejected.

### 1.5 `lab_results.nsp_name` — ~~vestigial column~~ RESOLVED, not an issue
**Correction:** checked against the actual ingestion code — `nsp_name` is not dead. `ScoreRowValidationService` uses it to match a score-sheet row to a `Learner` by name, and `LabResultCommitService` persists it on the entity and into the audit payload. `V20` only reverted the *identity/uniqueness* key back to `(learner_id, lab_id)`; it never claimed `nsp_name` itself was unused, and it isn't. The `NOT NULL` is legitimate. No fix needed.

### 1.6 `cohorts.is_locked` vs. `cohorts.lifecycle_state` — overlapping state, one is off-model — MEDIUM
```sql
lifecycle_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
    CHECK (lifecycle_state IN ('DRAFT','REFERENCE_ACCEPTED','STOOD_UP')),
is_locked       BOOLEAN NOT NULL DEFAULT FALSE,
```
Two columns encode overlapping "what state is this cohort in" information, but `lifecycle_state`'s `CHECK` doesn't include a `LOCKED` value at all — so "locked" lives entirely outside the enum as an orthogonal boolean, with an unstated rule for which `lifecycle_state` values it's legal to combine with `is_locked = true`. This is the kind of two-column state modeling that produces `2^n` cell combinations, most of which are meaningless, with nothing in the schema ruling them out.

**Fix:** either fold `LOCKED` into `lifecycle_state`'s enum (if it's a true additional lifecycle stage) or add a `CHECK` documenting which lifecycle states a lock is valid against (e.g., `CHECK (NOT is_locked OR lifecycle_state = 'STOOD_UP')`) so the combination space is actually constrained.

**Status: FIXED (`V30`).** `CohortService.lockCohort()` already hard-enforces "only a `STOOD_UP` cohort can be locked" in Java, so the second option above just codifies an existing app guarantee — no behavior change. Added `CHECK (NOT is_locked OR lifecycle_state = 'STOOD_UP')`; verified it rejects `is_locked=true` on a non-`STOOD_UP` row.

### 1.7 `learners` conflates global identity with per-cohort enrollment — MEDIUM (design risk, not a live bug)
```sql
learner_id VARCHAR(50) NOT NULL UNIQUE,
email      VARCHAR(254) NOT NULL UNIQUE,
cohort_id  UUID NOT NULL REFERENCES cohorts(id),
```
`learner_id` and `email` are unique **globally**, but each `learners` row also belongs to exactly one cohort. That means a person can only ever appear in the system once, in one cohort, for all time — a returning learner in a second cohort would collide on `learner_id`/`email` uniqueness and fail to insert. Contrast this with the `instructor_contacts` / `instructor_specialization_assignments` pair, which deliberately splits a **global** identity table from a **per-scope** junction table for the exact same shape of problem (an instructor works across cohorts/specializations). `learners` doesn't get the same treatment.

If cross-cohort re-enrollment is out of scope by design, this is fine as-is — but it's a decision worth confirming and documenting explicitly, since the schema already shows the team knows the correct pattern (it's used one table over) and just didn't apply it here.

**Fix (if re-enrollment must be supported):** split into a global `learner_identities` (learner_id/email/full_name) + `learner_enrollments` (cohort_id, specialization_id, status) junction, mirroring `instructor_contacts`/`instructor_specialization_assignments`. If out of scope, add a one-line comment on the table stating that explicitly.

**Status: uniqueness scope FIXED (`V30`); full identity/enrollment split still open.** This turned out to be a confirmed mismatch, not a hypothetical: `LearnerRepository` already exposes `existsByLearnerIdAndCohortId`/`findByLearnerIdAndCohortId` — the app code was written assuming `learner_id` is unique *per cohort*, while the DB enforced it *globally*. `learner_id` is currently set to the learner's email at commit time (`ReferenceCommitService`), so the same person enrolling in a second cohort would fail to insert. No code path does a global (non-cohort-scoped) lookup by `learner_id` or `email` on this table, so relaxing the constraint is safe. Changed `UNIQUE(learner_id)` / `UNIQUE(email)` to `UNIQUE(cohort_id, learner_id)` / `UNIQUE(cohort_id, email)`, updated the `Learner` JPA entity to match, and verified: the same `learner_id`/`email` across two different cohorts now inserts successfully, while a duplicate within the same cohort still correctly fails. The bigger identity/enrollment table split (if ever needed for reasons beyond uniqueness — e.g. tracking one person's history across cohorts) is unchanged and still a product decision.

### What's *not* a violation (intentional, and worth leaving alone)
- `lab_results.max_score_snapshot` duplicates `labs.max_score` at write time — this is a deliberate historical snapshot (so a later change to a lab's max score doesn't retroactively change the meaning of past scores), not an oversight. Good pattern.
- `lab_reference_audit_log` (generic `table_name`/`record_id`/`field_name`/`old_value`/`new_value`) is an EAV-style audit log — normal for audit trails, not held to 3NF.
- `lab_reference_audit_log.deleted_user_email` duplicates `users.email` — intentional, because `changed_by` is `ON DELETE SET NULL`; without the snapshot, a deleted user's audit trail would go anonymous. Correct defensive design.
- `notification_settings` singleton via `CHECK (id = '00000000-...-001')` — clean way to guarantee at most one row without a separate lock table.

---

## 2. Referential integrity gaps (missing FKs)

The original V1 tables are consistent about FK'ing every actor/audit column to `users(id) ON DELETE SET NULL`. Several tables added later dropped that discipline — the columns are still named `triggered_by`/`created_by`/`updated_by` but are plain `UUID` with **no FK constraint at all**:

| Table | Migration | Columns missing FK to `users(id)` |
|---|---|---|
| `cohort_gate4_jobs` | V7, V8 | `triggered_by`, `created_by`, `updated_by` |
| `cohort_sync_jobs` | V9 | `triggered_by`, `created_by`, `updated_by` |
| `cohort_sync_files` | V10 | `created_by`, `updated_by` |
| `sync_schedules` | V17 | `created_by`, `updated_by` |

Compare `cohort_standup_jobs` (V3/V5), which does it correctly:
```sql
triggered_by UUID REFERENCES users(id) ON DELETE SET NULL,
created_by   UUID REFERENCES users(id) ON DELETE SET NULL,
updated_by   UUID REFERENCES users(id) ON DELETE SET NULL,
```
Without the FK, the database will happily store a `triggered_by` that points at nothing (typo, deleted-and-never-cleaned-up user, wrong table entirely) and no one will find out until a report tries to join it. This is a real integrity hole, not a style nit — the fix is cheap and low-risk since it only *adds* a constraint (worth checking existing data for orphans before applying, in case any already exist).

**Fix:** one migration adding the four missing FKs, `ON DELETE SET NULL` to match the established convention.

**Status: FIXED (`V30`).** Added the 11 missing FK constraints across the four tables, tested against a disposable Postgres container replaying `V1`–`V29` first — applies cleanly, no orphaned data in a fresh DB (existing environments should check for orphans before deploying, per the caution above).

Related, more minor: `cohort_gate4_jobs.cohort_id`, `cohort_sync_jobs.cohort_id`, `cohort_sync_files.cohort_id`/`sync_job_id`, and `sync_schedules.cohort_id` all reference `cohorts(id)`/parents with **no explicit `ON DELETE` clause** (defaults to `NO ACTION`), where the V1 tables consistently spell out `ON DELETE RESTRICT` or `ON DELETE SET NULL`. Functionally similar to `RESTRICT` in Postgres, but worth aligning for consistency/readability.

---

## 3. Indexing issues (optimization)

### 3.1 Fully redundant duplicate index — `modules`
```sql
CONSTRAINT uq_module_code UNIQUE (specialization_id, code)   -- creates an implicit unique index
CREATE INDEX idx_modules_spec_code ON modules (specialization_id, code);  -- exact same columns, same order
```
`idx_modules_spec_code` is byte-for-byte redundant with the index the `uq_module_code` constraint already creates. It adds write overhead (every insert/update maintains two identical indexes) and storage for zero read benefit.

**Fix:** `DROP INDEX idx_modules_spec_code;`

**Status: FIXED (`V30`).**

### 3.2 Redundant single-column index shadowed by a `UNIQUE` constraint — `learners`
```sql
learner_id VARCHAR(50) NOT NULL UNIQUE,   -- implicit unique index on (learner_id)
...
CREATE INDEX idx_learners_learnerid ON learners (learner_id);  -- same single column
```
Same problem as 3.1: the column-level `UNIQUE` already gives Postgres an index to satisfy any `WHERE learner_id = ?` lookup. `idx_learners_learnerid` is dead weight.

**Fix:** `DROP INDEX idx_learners_learnerid;`

**Status: FIXED (`V30`).**

### 3.3 Single-column index shadowed by a composite index's leading column — `cohort_gate4_jobs`, `cohort_sync_jobs`
```sql
-- cohort_gate4_jobs
CREATE INDEX idx_gate4_jobs_cohort_id     ON cohort_gate4_jobs (cohort_id);
CREATE INDEX idx_gate4_jobs_cohort_status ON cohort_gate4_jobs (cohort_id, status);

-- cohort_sync_jobs
CREATE INDEX idx_sync_jobs_cohort_id     ON cohort_sync_jobs (cohort_id);
CREATE INDEX idx_sync_jobs_cohort_status ON cohort_sync_jobs (cohort_id, status);
```
A composite B-tree index on `(cohort_id, status)` already serves any query filtering on `cohort_id` alone (leftmost-prefix rule) just as well as the plain `(cohort_id)` index would. The single-column index in each pair is redundant.

**Fix:** drop `idx_gate4_jobs_cohort_id` and `idx_sync_jobs_cohort_id`.

**Status: FIXED (`V30`).**

### 3.4 `lab_results`: redundant leading-column index
```sql
CONSTRAINT uq_lab_result UNIQUE (learner_id, lab_id)   -- implicit unique index, learner_id leading
CREATE INDEX idx_results_learner ON lab_results (learner_id);  -- redundant given the above
```
Same leftmost-prefix reasoning as 3.3 — `idx_results_learner` adds nothing the unique constraint's index doesn't already provide.

**Fix:** `DROP INDEX idx_results_learner;` (`idx_results_lab`, `idx_results_run`, `idx_results_instructor` are all fine — none of them match a leading prefix of `uq_lab_result`.)

**Status: FIXED (`V30`).**

### 3.5 Missing index — `notifications.cohort_id`
`notifications` is indexed on `ingestion_run_id`, `status`, and `sync_job_id` (V28), but **not** on `cohort_id`, despite `cohort_id` being a plausible/likely filter for any "notifications for this cohort" admin view. Given the redundancy noted in §1.3, this is the one place where the missing index actually matters operationally.

**Fix:** `CREATE INDEX idx_notifications_cohort ON notifications (cohort_id);` — or fold into a composite `(cohort_id, status)` if that's the dominant query shape.

**Status: FIXED (`V30`).** Added the single-column index; can be revisited as a composite if a specific query shape needs it later.

### 3.6 Missing race-guard on `cohort_gate4_jobs`
`cohort_standup_jobs` and `cohort_sync_jobs` both enforce "at most one RUNNING job per cohort" at the DB level via a partial unique index:
```sql
CREATE UNIQUE INDEX uq_cohort_standup_job_running ON cohort_standup_jobs (cohort_id) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX uq_sync_jobs_cohort_running    ON cohort_sync_jobs   (cohort_id) WHERE status = 'RUNNING';
```
`cohort_gate4_jobs` has no equivalent. If the same "one active gate-4 run per cohort" invariant is supposed to hold (it mirrors the other two job tables closely enough to suggest it should), it's currently only enforced — if at all — in application code, which is race-able under concurrent requests.

**Fix:** if the invariant applies, add `CREATE UNIQUE INDEX uq_gate4_jobs_cohort_running ON cohort_gate4_jobs (cohort_id) WHERE status = 'RUNNING';`. If it genuinely doesn't apply to gate-4, no action needed — but worth a one-line comment saying so, since the asymmetry otherwise looks like an omission.

**Status: FIXED (`V30`).** Added the guard for parity with the sibling job tables; the gate-4 job lifecycle mirrors them closely enough (RUNNING/COMPLETED/FAILED, one-at-a-time semantics implied by the pattern) that the omission looked accidental rather than intentional.

---

## 4. Other schema-design notes (lower priority)

- **Enum-via-`CHECK` churn on `audit_event.event_type`:** four separate migrations (V11, V19, V25, V27) exist solely to `DROP CONSTRAINT` / `ADD CONSTRAINT` the same check list to append one new value each time. This is the documented, deliberate design choice ("VARCHAR + CHECK for all state columns, NOT native ENUM" — V1 header comment), so it's not a bug, but it is a recurring migration-authoring cost. If new event types keep being added at this rate, a lookup table (`event_types(code PK)`) with a plain FK from `audit_event.event_type` would let new values be inserted as data instead of a schema migration — worth reconsidering if this cadence continues.
- **Hash column type inconsistency:** `ingestion_runs.file_sha256` and `cohort_sync_files.file_sha256` are `CHAR(64)`, but `lab_results.row_value_hash` (also a fixed-length hex digest, per its name) is `VARCHAR(64)`, and `quick_xor_hash` is `VARCHAR(128)` in both places it appears. Not a functional bug, just an inconsistent convention across otherwise-parallel columns.
- **JSONB columns have no GIN indexes** (`error_report_json`, `payload_json`, `gate_events_json`, `sync_events_json`, `bundle_json`, `incoming_payload_json`). Fine as long as these stay write-mostly/debug-read blobs never filtered by key; flag this if a future feature ever needs `WHERE payload_json @> '{...}'`-style querying.

---

## 5. Summary table

| # | Finding | Type | Severity | Status |
|---|---|---|---|---|
| 1.1 | `learners.cohort_id` redundant with `specialization_id → specializations.cohort_id`, unenforced | Normalization | High | **Fixed (`V31`)** — trigger guard, column kept |
| 1.2 | `ingestion_conflicts.cohort_id` fully derivable from `ingestion_run_id` | Normalization | Medium | **Fixed (`V31`)** — trigger guard, column kept |
| 1.3 | `notifications.cohort_id`/`sync_job_id`/`ingestion_run_id` triple path to cohort, no consistency rule | Normalization | Low/Medium | **Fixed (`V31`)** — trigger guard, columns kept |
| 1.4 | `notifications` recipient columns: unenforced polymorphic FK pairing | Integrity | Medium | **Fixed (`V31`)** |
| 1.5 | `lab_results.nsp_name` — thought vestigial | Dead column | — | **Resolved: not an issue** (actively used, see §1.5) |
| 1.6 | `cohorts.is_locked` vs `lifecycle_state` overlapping/unconstrained state | Modeling | Medium | **Fixed (`V30`)** |
| 1.7 | `learners` global uniqueness vs. app code's cohort-scoped assumption | Integrity (confirmed bug) | High | **Fixed (`V30`)** — uniqueness scope only; full identity/enrollment split still open |
| 2 | Missing FKs on `triggered_by`/`created_by`/`updated_by` in 4 tables | Integrity | High | **Fixed (`V30`)** |
| 3.1 | Duplicate index `idx_modules_spec_code` vs `uq_module_code` | Optimization | Low | **Fixed (`V30`)** |
| 3.2 | Duplicate index `idx_learners_learnerid` vs `learner_id UNIQUE` | Optimization | Low | **Fixed (`V30`)** |
| 3.3 | Redundant single-col indexes on `cohort_gate4_jobs`/`cohort_sync_jobs` | Optimization | Low | **Fixed (`V30`)** |
| 3.4 | Redundant `idx_results_learner` on `lab_results` | Optimization | Low | **Fixed (`V30`)** |
| 3.5 | Missing index on `notifications.cohort_id` | Optimization | Medium | **Fixed (`V30`)** |
| 3.6 | No RUNNING-job race guard on `cohort_gate4_jobs` (unlike sibling job tables) | Integrity/Concurrency | Medium | **Fixed (`V30`)** |

**Nothing is open anymore.** `V30__schema_review_fixes.sql` (+ the `Learner` entity update) covers the mechanical fixes and the `learners` uniqueness scope; `V31__cohort_consistency_guards.sql` covers 1.1–1.4 via triggers/a `CHECK` rather than the bigger column-removal refactor. Both were tested by replaying every prior migration plus the new one against a disposable Postgres container and exercising every new invariant directly in both directions (valid case succeeds, invalid case is rejected with a clear error) — not just written and assumed correct:

- `V30`: cross-cohort `learner_id`/`email` reuse now succeeds; same-cohort duplicates still correctly fail; `is_locked=true` on a non-`STOOD_UP` cohort is correctly rejected.
- `V31`: a `learners`/`ingestion_conflicts`/`notifications` row consistent with its parent's `cohort_id` inserts fine; one deliberately mismatched on any of the three paths (specialization, ingestion run, sync job) is rejected; a notification with neither run nor sync job (only `cohort_id`) still inserts fine; a valid recipient pairing inserts fine, an incomplete or double-populated pairing is rejected.

The only thing left on the table, by choice, is the *larger* refactor variant of 1.1–1.3 — dropping the redundant columns entirely and rewriting the queries/entities that read them directly instead of joining. That's a bigger, riskier change than the actual defect warranted (an unenforced invariant, not the redundancy itself), so it's available as future work rather than something done here.
