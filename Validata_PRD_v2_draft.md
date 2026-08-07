# Validata — Product Requirements Document (v2, SharePoint Pivot)

> **Status:** DRAFT — for team + PO review
> **Supersedes:** Lab Results Validation App PRD v1.0 (May 2026)
> **Date:** 2026-07-20
> **Owner:** QA / Product
> **Companion docs:** `Validata_Technical_Proposal_v2.docx`, `Validata_Decision_Log_v2.docx`

**Legend**

| Symbol | Meaning |
|---|---|
| ✅ SETTLED | Agreed in session — build to this |
| ⚠ OPEN | Decision still required (see §8) |
| 🚫 BLOCKER | Work cannot start until resolved |
| 📣 ESCALATE | Flagged to Product Owner |
| `[OPEN — PO to provide]` | Literal value to be supplied; shape is fixed |

---

## §0 — Context & the Pivot

### 0.1 What changed

Validata (Lab Results Validation App) validates instructor-submitted lab grades against configured reference data and commits clean results to a relational database for downstream analytics.

**v1** ingested grades via a **manual CSV upload** flow: instructors logged in, downloaded a template, filled it, and uploaded it through the app.

**v2 pivots the ingestion source to SharePoint.** Instructors already maintain grading data in shared Excel workbooks on SharePoint. Rather than force a new workflow, Validata reads those workbooks directly via the **Microsoft Graph API**. The manual upload flow and the entire instructor-facing frontend are **retired**.

The proven back half of the system — the validation pipeline, reference-data hierarchy, audit trail, and database schema — is **retained** and adapted.

### 0.2 From → To

| Dimension | v1 | v2 |
|---|---|---|
| Grade ingestion | Manual CSV upload via app | SharePoint Excel via Microsoft Graph API |
| Reference-data setup | Individual admin CRUD + bulk CSVs | Single SharePoint folder link → validated bundle |
| Instructor app access | Login, dashboard, template, uploads | **None** — SharePoint producer + email recipient only |
| Score format | Per-lab `max_score`, raw score | Weighted decimal 0.0–1.0 → ×100, `max_score` fixed at **100** |
| Attempts | `attempt_number` 1 or 2, both stored | Single result per (learner, lab); duplicates flagged for manual fix (§2.4) |
| Identity matching | Email / name | **LearnerID** and **InstructorID** (primary) |
| Trigger | User-initiated upload | Async job: admin-triggered stand-up; weekly grading sync (Epic B) |

### 0.3 Product boundary (important)

Validata's responsibility **ends at a clean, validated relational dataset** (the `lab_results` table plus the reference-data hierarchy). ✅ **Reporting views and Power BI are owned by the Data Engineering team**, which consumes our database directly. Building `vw_*` reporting views, DAX, or dashboards is **out of scope** for Validata.

---

## §1 — Personas & Scope

### 1.1 Personas

| Persona | In-app? | Responsibilities |
|---|---|---|
| **Admin** | Yes (only in-app role) | Creates cohorts, provides SharePoint links, runs cohort stand-up, reviews validation results, accepts reference data, triggers/monitors grading syncs, reads audit log |
| **Instructor** | **No** | Maintains grading workbooks in SharePoint; receives email notifications. Exists in Validata only as a **notification contact** (InstructorID + email + name), *not* a login-capable user |
| **Data Engineering** | No (DB consumer) | Owns reporting layer downstream of Validata's DB |
| **Learner** | No | Never contacted; data subject only |

### 1.2 In scope

- Admin authentication & admin-only RBAC (trimmed — §7)
- **Cohort stand-up** flow: link → folder → reference files → accept → empty score-sheet validation (Epic A)
- Reference-data model sourced entirely from the SharePoint reference bundle (5 files)
- **Weekly grading ingestion** from SharePoint score sheets (Epic B)
- Email notifications to instructors and admins (Epic C)
- Audit trail + SharePoint version tracking (Epic D)
- Admin auth & RBAC, trimmed to a single role (Epic E)
- **Greenfield rebuild** — `dev` archived to `v1`; new `dev` built fresh, porting only retained modules (auth, validation core, email infra) from v1 (§8 D-6). No in-place migration.

### 1.3 Out of scope

- ❌ Instructor-facing UI, instructor login/auth, template download, my-uploads, corrections-only re-upload (all **removed**)
- ❌ Manual CSV upload of grades or reference data (replaced by the SharePoint link)
- ❌ Reporting views, Power BI content, DAX (owned by Data Engineering — §0.3)
- ❌ Quizzes — **labs only**
- ❌ Instructor-to-module **authorization** enforcement (old rule V15 — dropped; §2.6)
- ❌ Score computation, pass-mark logic, learner accounts, LMS integration (carried over from v1)

---

## §2 — Data Model Changes

> **Concrete DDL:** the full greenfield schema is in `Validata_v2_schema.sql` (drops into the new `dev` branch as `V1__create_tables.sql`). This section is the conceptual summary; the SQL is authoritative.
>
> Finalized design decisions: `max_score` kept as a column defaulted to 100 (not dropped); instructor↔lab link is dynamic via `lab_results.instructor_contact_id` (no static assignment table — `user_module_assignments` dropped with V15); reporting views **removed** from our schema (DE owns reporting); lab-title match is **case-insensitive**; all state columns are `VARCHAR + CHECK` (not native enum). Greenfield rebuild — no ALTER migrations.

### 2.1 New identity columns

| Table | Column | Example | Purpose |
|---|---|---|---|
| `learners` | `learner_id` (varchar, unique) | `DEG-2026-001` | Primary match key from grading + roster |
| **`instructor_contacts`** (new table) | `instructor_id` (varchar, unique) + `email`, `full_name`, lab/module association | `INS-001` | Passwordless notification contact; maps grading rows → who to notify. **Not** a `users` row (§7) |
| `modules` | `code` (varchar, unique within specialization) | `BEM01` | Business identifier only — ✅ **revised**: not a sheet-name lookup key; modules/sheets are never matched by name (§4.3) |

> Instructors moved **off `users`** onto `instructor_contacts` — `users` is now admins-only (§7). The `users.instructor_id` column proposed in the Technical Proposal v2 is superseded by this.

### 2.2 Score model

- ✅ `max_score` is **fixed at 100** for all labs. The per-lab configurable max is retired.
- ✅ Ingested `score = TotalScore × 100` (e.g. `0.9 → 90.00`).
- Old rule **V14 (max_score drift)** is **removed** — there is no configured per-lab max to drift from.
- Old rule **V5** becomes simply `0 ≤ score ≤ 100`.
- ⚠ **OPEN — rounding rule.** Define behavior for non-round conversions (e.g. `0.876 → 87.6`): decimal places retained, rounding mode. (§8)

### 2.3 SharePoint provenance columns

| Table | Column | Purpose |
|---|---|---|
| `csv_uploads` (audit) | `sharepoint_version_id` | Which file version was ingested |
| `csv_uploads` (audit) | `sharepoint_file_url` | Source file link |
| `csv_uploads` (audit) | `file_sha256` | Deduplication (existing) |

*(The `csv_uploads` table name is retained for continuity but now records SharePoint sync runs, not manual uploads. Rename is a §8 housekeeping decision.)*

### 2.4 Attempts & duplicate rows ✅

- ✅ The `attempt_number` **concept is scrapped** from the sheet — instructors no longer record an attempt column.
- ✅ **v2 assumes a single result per (review date, learner, lab).** Row uniqueness is keyed on **`(review_date, learner_id, lab_id)`** — `review_date` now plays the role the old `attempt_number` played: a learner can have multiple committed results for the same lab, one per distinct review date. `total_score` is **not** part of the key; it is the tracked value that drives change detection.
- ✅ **DECIDED — collision handling splits by *where* the collision occurs** (full mechanism in §4.3):
  - **Changed (re-grade)** — incoming row matches a *committed* record (same review date, learner, lab) but with a different `total_score` → **update in place, log prior value, notify admin**. There is a clear new truth, so it auto-applies (recoverable via the logged prior value).
  - **Duplicate (conflict)** — same `(review date, learner, lab)` appears **≥2× within one file** → ambiguous, no authoritative winner → **not recorded; held on a conflict queue for manual resolution** (existing vs. incoming, GitHub-merge style).
- Impacts: DB uniqueness key `(review_date, learner_id, lab_id)` with **upsert** on `total_score` change; conflict-queue feature (Epic B B10); supersedes Decision-Log item 14 ("store both attempts") and the earlier blunt "flag as error" framing.

### 2.5 Instructor as notification contact

- ✅ Instructors are **lightweight contact records** in the dedicated **`instructor_contacts`** table (§2.1) — `instructor_id`, `email`, `full_name`, lab/module association — populated from the reference bundle at stand-up.
- ✅ **No** password, `must_change_password`, login, session, or `users` row for instructors. All instructor-auth machinery from v1 is removed (§7).

### 2.6 Removed rule

- ✅ **V15 (instructor authorized for module)** is **dropped entirely** — with no instructor auth, there is nothing to enforce it against.

---

## §3 — Epic A: Cohort Stand-Up (Admin)

The admin stands up a cohort by pointing Validata at a single SharePoint folder. Validata runs a staged, fail-fast, **atomic** validation pipeline with a durable checkpoint, streaming progress to the admin throughout.

### 3.1 Cohort state machine

```
DRAFT ──submit link + run──▶ (Gate 1 Link) ──▶ (Gate 2 Folders)
   ▲                                                   │ pass
   │ discard / reset                                   ▼
   │                                          (Gate 3 Reference files, atomic)
   │◀── fail: errors shown in DRAFT ───────────────────┤ pass
   │     (admin fixes in Excel, retries)               ▼
   │                                            admin clicks ACCEPT
   │                                                   ▼
   │                                     REFERENCE_ACCEPTED ★ (frozen checkpoint)
   │                                                   │
   │                                          (Gate 4 Empty score sheets)
   │◀── fail traced to reference data ─────────────────┤
   │     (discard → DRAFT, redo)                       │ fail traced to sheet
   │                                     ┌─────────────┘ (fix sheet in SharePoint,
   │                                     │                re-run Gate 4 only)
   │                                     ▼
   │                                  STOOD_UP ──▶ LOCKED (existing cohort-lock)
```

### 3.2 SharePoint folder contract

```
<cohort folder>                        ← admin provides link to this
├── [OPEN — PO to provide]/            ← reference-data subfolder (fixed literal name)
│   ├── [OPEN — file 1 name]           ← 5 reference files, exact names,
│   ├── [OPEN — file 2 name]              validated by content-type
│   ├── [OPEN — file 3 name]              (includes learner roster)
│   ├── [OPEN — file 4 name]
│   └── [OPEN — file 5 name]
└── [OPEN — PO to provide]/            ← scores subfolder (fixed literal name)
    └── <instructor grading workbooks> ← validated at Gate 4 (empty) and Epic B (graded)
```

- ✅ Both subfolder names are **fixed literals** `[OPEN — PO to provide]`.
- ✅ The 5 reference files are matched by **exact filename** and **validated against expected content-type** `[OPEN — PO to provide file names + shapes]`.
- ⚠ **OPEN — link format.** The link is an **API-resolvable canonical path** `[OPEN — PO/IT to confirm exact form]` (site-relative path vs. sharing URL vs. drive-item path). Must resolve to a folder within the sanctioned tenant site.
- ✅ Reference-data setup is **all-or-nothing** across every gate below.

### 3.3 Stories & Acceptance Criteria

> AC convention: **Given / When / Then**. Each story = one Jira story; sub-bullets under AC = candidate sub-tickets.

---

#### A1 — Create & name a cohort
As an admin, I create and name a cohort so I can begin standing it up.

- **AC1** — Given I am an authenticated admin, When I submit a valid cohort name, Then a cohort is created in state `DRAFT` with `is_active = true` and appears in the cohort list marked **Draft**.
- **AC2** — Given a cohort name already exists in the program, When I submit that name, Then creation is rejected with `"Cohort name must be unique"` and no cohort is created.
- **AC3** — Given a cohort is in `DRAFT`, Then no reference data, score data, or grading sync is available for it yet.

#### A2 — Provide the SharePoint folder link
As an admin, I attach the SharePoint folder link to a draft cohort.

- **AC1** — Given a `DRAFT` cohort, When I submit a folder link, Then the link is stored against the cohort and a stand-up validation job can be started.
- **AC2** — Given a stand-up job is already running for this cohort, When I try to start another, Then it is rejected — **one stand-up job per cohort at a time** (see A11).

#### A3 — Gate 1: Link-level validation
As Validata, I confirm the link is resolvable and accessible before doing anything else.

- **AC1** — Given a submitted link, When Gate 1 runs, Then Validata resolves it via Microsoft Graph using service credentials.
- **AC2** — Given the link cannot be resolved or is not accessible with our credentials, Then Gate 1 **fails**, the cohort stays `DRAFT`, and the admin sees a precise error, e.g. `"Cannot access the SharePoint folder at <link>. Check the path and that Validata has been granted access."`
- **AC3** — Given the link resolves to a file (not a folder) or a location outside the sanctioned site, Then Gate 1 **fails** with a clear, specific message.
- **AC4** — Given Gate 1 fails, Then no later gate runs (fail-fast).

#### A4 — Gate 2: Folder-structure validation (atomic)
As Validata, I confirm the expected subfolders exist as a complete set.

- **AC1** — Given Gate 1 passed, When Gate 2 runs, Then Validata verifies **both** fixed-literal subfolders (reference-data and scores) exist under the cohort folder.
- **AC2** — Given one or more expected subfolders are missing, Then Gate 2 **fails atomically** (all-or-nothing), the admin is alerted, and the error names exactly which subfolder(s) are missing.
- **AC3** — Given Gate 2 fails, Then the cohort stays `DRAFT` and no reference data is read.

#### A5 — Gate 3: Reference-file validation (atomic)
As Validata, I validate the 5 reference files as a single all-or-nothing bundle.

- **AC1** — Given Gate 2 passed, When Gate 3 runs, Then Validata verifies all **5 reference files are present by exact name** in the reference subfolder.
- **AC2** — Given any expected file is missing or misnamed, Then Gate 3 **fails atomically** and names the missing/misnamed file(s).
- **AC3** — Given a file's content does not match its expected content-type/shape, Then Gate 3 **fails atomically** with the file name and the mismatch.
- **AC4** — Given content-level errors exist (e.g. **duplicate learner email**, duplicate LearnerID, missing required column, unparseable cell), Then Gate 3 **fails atomically** and reports **every** error with exact location — file, sheet/row, and rule violated.
- **AC5** — Given **any** error at any level in Gate 3, Then **nothing is committed** to the database (full atomicity — §3.2) and the cohort stays `DRAFT`.
- **AC6** — Given Gate 3 passes with zero errors, Then the validated reference bundle is held pending explicit admin acceptance (A6) — it is **not** auto-committed.

#### A6 — Explicit Accept → REFERENCE_ACCEPTED checkpoint
As an admin, I review the validated reference data and explicitly accept it.

- **AC1** — Given Gate 3 passed, When I view the result, Then I see a summary of what will be created (counts of specializations, modules, labs, learners, instructor contacts).
- **AC2** — Given I click **Accept**, Then the full reference hierarchy (cohort → specializations → modules → labs → learners + instructor contacts) is committed in **one atomic transaction** and the cohort moves to `REFERENCE_ACCEPTED`.
- **AC3** — Given the cohort is `REFERENCE_ACCEPTED`, Then the reference data is **frozen** — it cannot be edited or added to in-app for the life of the cohort (✅ decided; see §8 R-1). The only escape is discard/reset (A10).
- **AC4** — Given I do not accept, Then nothing is committed and the cohort stays `DRAFT`.
- **AC5** — Given the Accept action, Then it is written to the audit trail (who, when, which SharePoint version — Epic D).

#### A7 — Gate 4: Empty score-sheet validation
As Validata, I confirm the empty score sheets conform to the accepted reference data, so that any later error is provably instructor input — not setup drift.

> ✅ **DECIDED — validation route (read-only).** Validata does **not** own or generate the score sheets — the admin brings them. Gate 4 **validates** the sheets the admin supplies against the accepted reference data. Validata reads them; it never writes them.

- **AC1** — Given `REFERENCE_ACCEPTED`, When Gate 4 runs, Then Validata reads the score-sheet **empty state** (no scores entered) from the scores subfolder, **read-only**.
- **AC2** — Given the score sheets, Then Validata processes every sheet that isn't a known metadata sheet (Template, How-To, Ref) — ✅ **revised**, matching the actual implementation: sheet name carries no matching semantics; there is no `modules.code` lookup via sheet name.
- **AC3** — Given the score sheets, Then Validata verifies required columns are present (Review Date, Name of NSP, Lab Title, Total Score, Reviewer — per the actual template; final list per §8).
- **AC4** — Given the score sheets, Then Validata verifies referential conformance to the source of truth: every **Name of NSP maps to a learner** in the accepted roster; every **Lab Title maps to a lab configured under that learner's specialization** (unknown lab title vs. lab-exists-under-a-different-specialization are distinguished).
- **AC5** — Given a conformance mismatch, Then Gate 4 **fails** and reports **sheet + row + rule** for every mismatch. If the mismatch traces to the **sheet**, the admin fixes it in SharePoint and re-runs **Gate 4 only**. If it traces to **reference data** (should be impossible given Gate 3, but must not deadlock), the admin uses **discard/reset** (A10) to redo from Gate 1.
- **AC6** — Given Gate 4 passes, Then the cohort moves to `STOOD_UP`.

#### A8 — Cohort STOOD_UP (readiness)
- **AC1** — Given `STOOD_UP`, Then the cohort is eligible for the weekly grading sync (Epic B) and no earlier one is.
- **AC2** — Given `STOOD_UP`, Then the admin can apply the existing **cohort lock** to move it to `LOCKED`.

#### A9 — Async execution & live progress
As an admin, I watch the stand-up progress in real time.

- **AC1** — Given I start a stand-up, Then it runs as an **async job** and the UI does not block.
- **AC2** — Given a running job, Then the admin sees per-gate visual state: `Pending → Running → Passed / Failed` for Gate 1, Gate 2, Gate 3, Accept, Gate 4.
- **AC3** — Given a gate fails, Then its state shows **Failed** with the error payload, and downstream gates show as not-run.
- **AC4** — Given the job completes (pass or fail), Then the final state is persisted and survives a page reload.

#### A10 — DRAFT error view, discard/reset & retry
As an admin, I rectify problems and retry, or start over.

- **AC1** — Given a cohort failed a gate, When I open it (shown as **Draft** with errors, or `REFERENCE_ACCEPTED` for Gate-4 failures), Then I see the full, exact error list from the failed gate.
- **AC2** — Given I have fixed the source data in Excel/SharePoint, When I retry, Then the pipeline re-runs from the appropriate point (from Gate 1 for reference failures; from Gate 4 for score-sheet failures at the checkpoint).
- **AC3** — Given a cohort in `REFERENCE_ACCEPTED`, When I choose **Discard / Reset**, Then the cohort returns to `DRAFT` (committed reference data is cleared), and I can resubmit the whole link. *(Not an edit — a do-over; consistent with "no in-app reference editing.")*
- **AC4** — Given any failure, Then the admin is **notified immediately** (in-app; email per Epic C, C5).

#### A11 — Concurrency guard
- **AC1** — Given a stand-up job is running for a cohort, Then no second job for the **same cohort** can start (one job per cohort).
- **AC2** — Given the weekly grading sync (Epic B), Then it only ever targets `STOOD_UP` cohorts — never one mid-stand-up.

---

## §4 — Epic B: Weekly Grading Ingestion (Admin / Scheduled)

The recurring core: on a schedule (and on-demand), Validata reads graded score sheets for `STOOD_UP` cohorts from SharePoint, detects what changed since last run, validates and commits new/changed results, and surfaces conflicts for manual resolution — all **read-only** against SharePoint, with **no write-back**.

### 4.1 Design principles

- **Read-only.** Graph is used only to locate files, read metadata (incl. `quickXorHash`), and **download file content**. Validata never writes to SharePoint. Required scope: `Sites.Read.All`.
- **Local parse.** Downloaded workbook bytes are parsed **in-process (Apache POI)**. The Graph **Excel workbook API is not used** — this avoids its cold-open timeouts, session fragility, and documented stale-cell reads, gives exact version↔data correspondence for hashing, and makes the whole pipeline testable from local `.xlsx` fixtures.
- **Our DB is the processed-state store.** No `Status=PROCESSED` write-back. "What's already done" is derived by comparing incoming rows to committed `lab_results`.
- **Partial success (unattended).** Unlike Epic A's attended all-or-nothing gates, Epic B **skips-and-continues**: a failure halts only the smallest self-contained unit (workbook → sheet → row). Valid grades are never held hostage by a neighbouring error.
- **Idempotent.** Re-running with no source changes commits nothing new and raises no false conflicts.

### 4.2 Flow

```
Trigger (Mon 08:00 GMT ⚠ + manual override)
   │
   ▼  for each STOOD_UP cohort
Discover scores-folder workbooks (Graph list)
   │
   ▼  per workbook
Read quickXorHash + version id (Graph, single-item GET)
   │  hash == last processed?  ──yes──▶ SKIP whole file (unchanged)
   │  no
   ▼
Download content (Graph) → parse locally (POI)
   │
   ▼  per sheet (metadata sheets skipped; sheet name otherwise unused for matching)
Structural check (required columns by header name)
   │
   ▼  per row where Status = READY
Normalize (LearnerID/InstructorID lookup · score ×100 · Excel date → ISO)
   │
   ▼
Field + referential validation
   │
   ▼
Classify vs DB + within-file  ──▶  new · unchanged · changed · duplicate
   │
   ▼
Upsert (insert new / update changed, log prior) · skip invalid · hold conflicts
   │
   ▼
Audit record (version id, hash, url, counts) · Conflict queue · Notify (Epic C)
```

### 4.3 Change-detection model

- **File short-circuit:** store `quickXorHash` + `sharepoint_version_id` per file per run. If the incoming file's `quickXorHash` equals the last processed value → **skip the whole file** (no download, no parse).
- **Row key (business key):** `(review_date, learner_id, lab_id)`, where `learner_id` resolves from **Name of NSP** (roster lookup) and `lab_id` derives directly from *Lab Title, cross-referenced against the learner's specialization* (✅ **revised** — no module/sheet-name/phase step at all; mirrors `Gate4ScoreSheetValidator`'s stand-up-time resolution exactly). Independent of row position and of sheet name.
- **Row value-hash:** hash over **Total Score** alone (✅ decided — supersedes the earlier Total Score + Review Date draft now that `review_date` has moved into the key). *Instructor is intentionally excluded — an instructor-only correction will not register as a change.*
- **Classification per READY row:**

| State | Detection | Action |
|---|---|---|
| **New** | key not in `lab_results` | validate → insert |
| **Unchanged** | key exists, value-hash matches | skip silently |
| **Changed (re-grade)** | key exists, value-hash differs | **upsert (update in place)**, log prior value, **notify admin** (informational) |
| **Duplicate (conflict)** | same key appears **≥2× within the same file** | **do not record**; hold both for **manual resolution** (existing vs. incoming) |

> The split: a collision **against the committed record** = a *change* (clear new truth → auto-apply + notify); a collision **among rows in the same file** = a *duplicate* (ambiguous → block + manual). Because prior values are always logged, an erroneous auto-update is fully recoverable.

### 4.4 Validation rules (remapped from v1 V1–V17)

| New ID | Rule | Stage | Origin |
|---|---|---|---|
| **S1** | Every non-metadata sheet is processed as a data sheet (Template/How-To/Ref skipped) — ✅ **revised**: sheet name carries no matching semantics at all; there is no module-code/phase resolution via sheet name (superseded the `Module-<phase>` design below) | Structural (per sheet) | new |
| **S2** | Required columns present **by header name** (order-agnostic) | Structural | V1 |
| **S3** | Workbook/sheet readable (POI can open) | Structural | V2 |
| **F1** | Required fields non-empty (LearnerID, Lab Title, Total Score, Review Date) | Field (per row) | V3 |
| **F2** | Total Score numeric and within range → `0 ≤ score×100 ≤ 100` | Field | V4, V5 |
| **F3** | Review Date is a valid date (Excel serial → ISO) | Field | V7 |
| **R1** | LearnerID maps to an **active** learner in this cohort | Referential | V9 |
| **R4** | Lab Title resolves to a lab directly within the learner's specialization — ✅ **revised**: mirrors `Gate4ScoreSheetValidator`'s stand-up check exactly; distinguishes **unknown lab title** (R4-UNKNOWN-LAB) from **lab configured under a different specialization** (R4-LAB-SPEC-MISMATCH). No module/sheet-name step in between (superseded the old R2/R3 module-resolution rules). | Referential | V10, V11, V12, V13 |
| **C1** | In-file duplicate `(review_date, learner_id, lab_id)` → conflict | Consistency | V16 |
| **C2** | Vs DB: changed → update+notify; unchanged → skip | Consistency | V17 |

> Removed: **V6** (attempt no longer on sheet), **V8** (email not the match key), **V14** (max_score fixed at 100), **V15** (no instructor auth to enforce).

### 4.5 Fail-scope (Decision Log Q11 — ✅ skip-and-continue, graduated)

| Failure | Halts | Proceeds |
|---|---|---|
| Can't fetch / POI can't open workbook | that **workbook** | all other workbooks |
| Sheet missing required columns / unreadable / unmatched name | that **sheet** | other sheets in the workbook |
| Row fails F/R validation | that **row** (skip + report) | all other rows |
| Row is in-file duplicate | those **rows** (hold) | all other rows |

- **No hard stop on failure rate.** If `rejected > 50%` of a sheet's rows, fire a **loud admin alert** (reused from v1) — but still commit the valid rows. A stop would only delay good data.

### 4.6 Stories & Acceptance Criteria

#### B1 — Sync trigger
As an admin, the sync runs on schedule, and I can also run it on demand.

- **AC1** — ⚠ Given the scheduled time (default **Monday 08:00 GMT** — Decision Log Q2, provisional), When it arrives, Then a sync run starts automatically for all `STOOD_UP` cohorts.
- **AC2** — Given I am an admin, When I trigger a manual sync (whole run, or a single cohort/file), Then it runs immediately with the same logic as the scheduled run.
- **AC3** — Given a sync is already running for a cohort, When another would start for it, Then the second is prevented (one run per cohort — mirrors Epic A A11).
- **AC4** — ⚠ Timezone/DST handling of the scheduled time is confirmed (§8, parked).

#### B2 — Discovery (STOOD_UP only)
- **AC1** — Given a sync run, When it enumerates work, Then it targets **only `STOOD_UP` (or `LOCKED`) cohorts** — never `DRAFT`/`REFERENCE_ACCEPTED`.
- **AC2** — Given a cohort's scores subfolder, When enumerated, Then Validata lists the grading workbooks via Graph (read-only).
- **AC3** — Given the folder was moved/renamed/permissions revoked since stand-up, Then that cohort's run **fails with a clear alert** and does not affect other cohorts (R-5d).

#### B3 — File-level change short-circuit
- **AC1** — Given a workbook, When the run starts on it, Then Validata reads its `quickXorHash` and `sharepoint_version_id` via a single-item GET.
- **AC2** — Given the `quickXorHash` equals the last **processed** value for that file, Then the file is **skipped entirely** (no download, no parse) and recorded as "unchanged" in the run summary.
- **AC3** — Given the hash differs (or no prior record exists), Then the file proceeds to download + parse.

#### B4 — Fetch & local parse (read-only)
- **AC1** — Given a changed file, When Validata ingests it, Then it **downloads the content** via Graph and parses it **locally (POI)** — the Excel workbook API is not called.
- **AC2** — Given a workbook POI cannot open (corrupt/unsupported), Then **that workbook fails** with a clear error; other workbooks continue (§4.5).
- **AC3** — Given a successful parse, Then the `quickXorHash` recorded for change detection is computed over **the exact bytes parsed** (version↔data correspondence).
- **AC4** — Given transient Graph errors (throttling/token), Then Validata honours `Retry-After` and retries with backoff before failing the file.

#### B5 — Sheet selection & structural validation
- **AC1** — Given a parsed workbook, When selecting sheets, Then every sheet that isn't a known metadata sheet (Template/How-To/Ref) is processed as a data sheet (✅ **revised** — no sheet-name-to-module resolution at all; an earlier `Module-<phase>` design was abandoned once real workbooks confirmed phase numbering doesn't correspond to anything usable per-specialization — module/lab resolution happens per row instead, at B7, via Lab Title + specialization).
- **AC2** — Given a data sheet, Then required columns are located **by header name** (order-agnostic); a missing required column fails **that sheet** (S2) with the sheet name + missing column.
- **AC3** — *(superseded by AC1 — there is no sheet-name matching left to fail.)*

#### B6 — Row normalization
- **AC1** — Given a data sheet, When selecting rows, Then every non-blank row is processed (✅ **revised** — the sheet has no `Status` column; the originally-assumed `Status = READY` filter never applied). A row with a **blank Total Score** is treated as not-yet-graded and skipped silently (no error, no commit) rather than rejected.
- **AC2** — Given a READY row, Then `score = TotalScore × 100` (⚠ rounding per §8 D-ROUND) with `max_score = 100`.
- **AC3** — Given a READY row, Then Review Date is converted from the Excel value to ISO `YYYY-MM-DD`; an unconvertible date is a row error (F3).
- **AC4** — Given a READY row, Then LearnerID and InstructorID are resolved by lookup; unresolved LearnerID is a row error (R1); unresolved InstructorID is recorded (affects notification routing, not commit).

#### B7 — Field & referential validation
- **AC1** — Given a normalized row, When validated, Then rules **F1–F3** and **R1–R4** (§4.4) are applied.
- **AC2** — Given any rule fails, Then the row is **skipped and reported** with row + rule + message; other rows continue.
- **AC3** — Given a sheet where `rejected > 50%` of READY rows, Then a **high-failure admin alert** is raised for that file (valid rows still commit).

#### B8 — Classification & change detection
- **AC1** — Given a valid row, When classified, Then it resolves to exactly one of **new / unchanged / changed / duplicate** per §4.3.
- **AC2** — Given an **unchanged** row, Then nothing is written and it is counted as skipped-unchanged.
- **AC3** — Given a **changed** row, Then the existing record is **updated in place**, the **prior value is logged** to the audit trail, and the admin is notified (B10/Epic C).
- **AC4** — Given a **duplicate** (same key ≥2× in one file), Then **neither incoming row is committed** and both are placed on the conflict queue (B9).

#### B9 — Commit (upsert, partial success)
- **AC1** — Given classified rows, When committing, Then **new** rows insert and **changed** rows update, keyed on `(review_date, learner_id, lab_id)`; invalid rows are skipped; conflicts are held.
- **AC2** — Given a run over a file, Then commit is **partial-success** — valid rows persist regardless of sibling failures.
- **AC3** — Given the same file re-processed with no changes, Then **zero** rows are inserted/updated and **no** conflicts are raised (idempotency).

#### B10 — Conflict review queue
As an admin, I resolve duplicate conflicts manually.

- **AC1** — Given duplicates were held, Then they appear on a **per-run conflict queue** showing the existing committed record (if any) alongside each conflicting incoming row (GitHub-merge style).
- **AC2** — Given a conflict, When I choose which row is authoritative (or reject all), Then my choice is committed and the resolution is audited.
- **AC3** — Given unresolved conflicts, Then they persist across runs until resolved (not silently dropped).

#### B11 — Audit record per file
- **AC1** — Given a processed file, Then an audit record captures: cohort, workbook name, `sharepoint_file_url`, `sharepoint_version_id`, `quickXorHash`, counts (read / committed-new / updated / skipped-invalid / skipped-unchanged / conflicts), status, timestamp, trigger (scheduled/manual).
- **AC2** — Given a changed row, Then the prior committed value is retained in the audit trail (supports recovery/forensics).

#### B12 — Per-file notification
- **AC1** — Given a file completes, Then the mapped instructor(s) receive a per-file summary (Epic C) with accepted/rejected counts and row-level errors.
- **AC2** — Given rejections or conflicts exist, Then the admin receives an alert (Epic C).
- **AC3** — Given an unresolved InstructorID, Then notification routing degrades gracefully (admin notified; §8 parked — bounce/invalid handling).

## §5 — Epic C: Notifications (Staged Outbox + Admin Moderation)

Notifications are **computed and staged** after each run, then **dispatched** either automatically (safe/internal/action-needed types) or after **admin moderation** (the outward-facing instructor batch). This is a transactional-outbox pattern with a review UI — it gives admins a quality gate on instructor-facing email while keeping internal alerts hands-off.

### 5.1 Design principles

- **Stage, then dispatch.** Every notification is persisted as a `Notification` (status `PENDING`) before any send. Generation is decoupled from delivery.
- **Hybrid policy, kept tiny for v1.** One **auto vs. hold** lever per notification *type* (§5.2) — no per-cohort/per-severity matrix. The only shipped setting is a global toggle for instructor emails.
- **Instructor emails HELD by default** (✅ decided) — the risky outward batch waits for admin Notify / Send-all.
- **Idempotent delivery.** A hard status machine (§5.3) makes every send-once; send-all touches only `PENDING`; re-sends of `SENT` items are no-ops. No double-emailing.
- **Unified review.** Pending notifications live on the **same Run-Review screen** as the results summary and the conflict queue (B10) — one place, not three.
- **Reuse existing rails.** Delivery is the existing `EmailService` (`@Async` SMTP + HTML template). No new mail infra. *(Cleanup: the now-dead `onInstructorProvisioned` welcome email is removed — §7.)*

### 5.2 Notification matrix (v1)

| Type | Recipient | Channel | Policy | Batching | Scope |
|---|---|---|---|---|---|
| Stand-up gate failure (Epic A) | admins | in-app + email | auto | immediate | per cohort |
| Cohort `STOOD_UP` | admins | in-app | auto | immediate | per cohort |
| **Grading results → instructor** | instructor (via InstructorID) | email | **HELD (default)** | per-run digest | **only their reviewed rows** |
| Grading run → admin | all active admins | in-app + email | auto | per-run digest | whole run |
| High-failure (`>50%` sheet) | admins | in-app + email | auto | immediate | per file |
| Conflicts awaiting resolution | admins | in-app + email | auto | immediate | per run |
| Changed-row (re-grade) notice | admins | in-app | auto | rolled into admin digest | per run |
| Unresolved InstructorID | admins | in-app | auto | rolled into admin digest | per file |
| **Any learner notification** | — | — | **never** | — | — |

### 5.3 Notification lifecycle

```
run completes → generate → PENDING
   auto types ──dispatch at run-end──▶ SENT  (│ on error ▶ FAILED ──retry──▶ SENT)
   held types ──await admin──▶ [Notify] / [Send all] ──▶ SENT
                            └─ [Dismiss] ─────────────▶ SKIPPED
```

### 5.4 Stories & Acceptance Criteria

#### C1 — Post-run staging (outbox)
As Validata, I stage every notification after a run so nothing sends before it's recorded.

- **AC1** — Given a sync run (Epic B) or a stand-up event (Epic A) completes, When notifications are generated, Then each is persisted as a `Notification` with status `PENDING`, its type, recipient, rendered payload, and links to the originating run/file/cohort.
- **AC2** — Given generation, Then no email is sent as a side effect of generation — dispatch is a separate step (auto per §5.2 or manual).
- **AC3** — Given a run produced zero notifiable outcomes, Then no notifications are staged (empty runs are silent).

#### C2 — Notification policy (auto vs. held)
- **AC1** — Given each notification type, When staged, Then its dispatch policy follows §5.2 (auto types dispatch at run-end; held types await admin).
- **AC2** — Given the shipped global setting **"auto-send instructor emails"** (default **OFF = hold**), When an admin enables it, Then instructor grading digests dispatch automatically on subsequent runs.
- **AC3** — Given no other per-type policy configuration exists in v1, Then per-cohort/per-severity policy is out of scope (§8 parked).

#### C3 — Instructor grading digest
As an instructor, I receive one clear summary of my graded rows per run.

- **AC1** — Given a run committed/rejected rows an instructor reviewed, Then **one per-run digest** is staged for that instructor covering **only the rows carrying their InstructorID**, across all sheets/files.
- **AC2** — Given the digest, Then it contains: run date, per-lab counts (accepted / rejected / updated), and each rejected row with `row + rule + message`, phrased for correction (✅ **revised** — no `Status` column to reset; correction is "fix the row and re-save the sheet").
- **AC3** — Given the instructor reviewed no rows in the run, Then no digest is staged for them.
- **AC4** — Given an instructor's rows appear across multiple sheets, Then they still receive a **single** digest (not one per sheet/file).

#### C4 — Admin run digest
- **AC1** — Given a run completes, Then a per-run digest is staged for **all active admins** summarising every cohort/file: counts (read / new / updated / skipped-invalid / skipped-unchanged / conflicts) and files flagged high-failure.
- **AC2** — Given changed-row (re-grade) notices and unresolved-InstructorID notices, Then they are **rolled into** this digest (not separate emails).
- **AC3** — Given the admin digest, Then it dispatches **automatically** (it is internal; no moderation needed).

#### C5 — Immediate action alerts
- **AC1** — Given a **stand-up gate failure**, a **high-failure sheet (`>50%`)**, or **conflicts awaiting resolution**, Then an alert is staged and dispatched **immediately** (auto) to admins via in-app + email — not held for the digest.
- **AC2** — Given a cohort reaches `STOOD_UP`, Then an in-app confirmation is raised (auto, no email).

#### C6 — Run-Review screen (unified)
As an admin, I review a run's results, conflicts, and pending notifications in one place.

- **AC1** — Given a completed run, When I open its Run-Review, Then I see the **results summary**, the **conflict queue** (B10), and the **pending notifications** list together.
- **AC2** — Given the pending notifications list, Then each item shows recipient, type, and the row-level descriptions/errors it will convey, with a **Notify** button; a **Send all** action sits at the top.
- **AC3** — Given held instructor digests, Then they are the items surfaced here for moderation (auto types appear as already-sent, for transparency).

#### C7 — Manual dispatch (send-one / send-all / dismiss)
- **AC1** — Given a `PENDING` notification, When I click **Notify**, Then it dispatches and moves to `SENT`.
- **AC2** — Given multiple `PENDING` notifications, When I click **Send all**, Then **only `PENDING`** items dispatch; `SENT`/`SKIPPED` are untouched.
- **AC3** — Given a `PENDING` notification I don't want to send, When I **Dismiss** it, Then it moves to `SKIPPED` and is never delivered.
- **AC4** — Given any dispatch or dismiss, Then who/what/when is written to the audit trail (accountability for outbound comms).

#### C8 — Lifecycle & idempotency
- **AC1** — Given a notification, Then its status transitions follow §5.3 (`PENDING → SENT / SKIPPED / FAILED`; `FAILED → SENT` on retry).
- **AC2** — Given an already-`SENT` notification, When a send is attempted again (double-click, overlapping Send-all), Then it is a **no-op** — no second email.
- **AC3** — Given a new run generates notifications while a prior run's items are still `PENDING`, Then the two sets are distinct (linked to their own run); the prior set is not silently superseded.

#### C9 — Delivery & failure handling
- **AC1** — Given dispatch, Then delivery uses the existing `EmailService` (`@Async` SMTP + HTML template).
- **AC2** — Given a send fails, Then the notification moves to `FAILED` with the error captured, and a batch send **continues** with the remaining items (log-and-continue, not abort).
- **AC3** — Given a `FAILED` notification, Then the admin can retry it from the Run-Review screen.
- **AC4** — ⚠ Bounced/invalid recipient handling is out of scope for v1 (§8 parked).

#### C10 — Learner-notification guard
- **AC1** — Given any run, event, or manual action, Then **no notification is ever addressed to a learner** — enforced regardless of data content.

#### C11 — Format sign-off
- **AC1** — 📣 Given the instructor and admin digest formats, Then the PO confirms them (Decision Log Q3); until then, drafts follow the proposal's examples and are marked provisional.

## §6 — Epic D: Audit & Version History

Everything Validata does must be reconstructable after the fact: what was ingested, from which file version, who triggered it, what changed, and what was sent. Epic D extends the existing audit backbone rather than replacing it.

### 6.1 Two audit surfaces (kept distinct)

- **Ingestion runs** — *per file, per run* records (extends the existing `csv_uploads`). Backs the DE team's forensics and the historical audit-log view.
- **Lifecycle events** — cohort/stand-up actions that aren't ingestions (link submitted, gate outcomes, Accept, discard/reset, lock, conflict resolutions). New lightweight `audit_event` store.
- Plus two existing stores reused as-is: **`LabReferenceAuditLog`** (prior-value history) and the **`Notification`** entity (outbound-comms audit, Epic C).

### 6.2 Data model

**Rename `csv_uploads` → `ingestion_runs`** (✅ D-4), extended:

| Field | Note |
|---|---|
| `id`, `cohort`, `workbook_filename` | which file |
| `sharepoint_file_url`, `sharepoint_version_id`, `quick_xor_hash`, `file_sha256` | provenance + dedup (§4.3) |
| `triggered_by` (User **or `SYSTEM`**), `trigger_type` (`SCHEDULED`/`MANUAL`) | ✅ D-2 |
| counts: `rows_read`, `committed_new`, `updated`, `skipped_invalid`, `skipped_unchanged`, `conflicts` | ✅ D-2 explicit headline columns |
| `status` (`PROCESSING`/`COMPLETED`/`PARTIAL`/`FAILED`/**`SKIPPED`**) | ✅ D-2 adds `SKIPPED` (hash short-circuit) |
| `error_report_json` (jsonb) | row-level detail |
| `created_at`, `updated_at` | |

**New `audit_event` table:**

| Field | Note |
|---|---|
| `id`, `event_type` | `LINK_SUBMITTED`, `GATE_FAILED`, `REFERENCE_ACCEPTED`, `DISCARD_RESET`, `COHORT_LOCKED`, `CONFLICT_RESOLVED`, … |
| `cohort`, `actor` (User or `SYSTEM`) | who/where |
| `occurred_at`, `payload_json` | when + event detail (e.g. which gate, SharePoint version at accept) |

### 6.3 Version-history strategy (3-layer — Decision Log item 15)

| Layer | Where | How |
|---|---|---|
| 1 | SharePoint document library | Versioning enabled by **IT** *(dependency, not our code — confirm)* |
| 2 | `ingestion_runs` | Store `sharepoint_version_id` + `sharepoint_file_url` + `quick_xor_hash` + `file_sha256` per run |
| 3 | Sync logic | `quickXorHash` short-circuit skips unchanged files (§4.3) |

### 6.4 Stories & Acceptance Criteria

#### D1 — Ingestion run audit record
- **AC1** — Given a file is processed in a run, When it completes, Then an `ingestion_runs` record is written with provenance (`sharepoint_file_url`, `sharepoint_version_id`, `quick_xor_hash`, `file_sha256`), `triggered_by`/`trigger_type`, all six count columns, status, and `error_report_json`.
- **AC2** — Given a file skipped by the hash short-circuit (§4.3), Then a record is still written with status `SKIPPED` (so "we saw it, nothing changed" is auditable).
- **AC3** — Given a system-scheduled run, Then `triggered_by = SYSTEM`, `trigger_type = SCHEDULED`; given a manual run, `triggered_by =` the admin, `trigger_type = MANUAL`.

#### D2 — Cohort / stand-up lifecycle audit
- **AC1** — Given any of link-submitted, gate failure, `REFERENCE_ACCEPTED`, discard/reset, cohort lock, or conflict resolution, Then an `audit_event` row is written with event type, cohort, actor, timestamp, and payload.
- **AC2** — Given `REFERENCE_ACCEPTED`, Then the payload captures **who, when, and the SharePoint version** the reference bundle was accepted from (satisfies A6 AC5).
- **AC3** — Given a gate failure, Then the payload records which gate and a summary of the error set.

#### D3 — Prior-value history (reuse `LabReferenceAuditLog`)
- **AC1** — Given a **changed row** (re-grade, B8 AC3), Then the prior committed value is written to the prior-value history before the update is applied.
- **AC2** — Given a reference force-edit (existing behaviour), Then it continues to log prior values through the same store.
- **AC3** — Given a wrongful auto-update, Then the prior value is retrievable for recovery.

#### D4 — Version tracking & dedup
- **AC1** — Given a file processed, Then its SharePoint `version_id` and content hashes are persisted on the run record (layer 2).
- **AC2** — Given a re-run where the file's `quickXorHash` is unchanged, Then it is deduped/skipped (layer 3) and recorded as `SKIPPED`.
- **AC3** — ⚠ Given layer 1 (SharePoint versioning) is an **IT dependency**, Then it is confirmed enabled on the library before go-live (§8 dependency, not Validata code).

#### D5 — Admin audit-log view
As an admin, I browse historical audit records across all runs and cohorts.

- **AC1** — Given the audit-log view, Then I can browse `ingestion_runs` and `audit_event` history, **filterable** by cohort, date range, status, and (for runs) instructor.
- **AC2** — Given a run record, Then I can open its row-level `error_report_json` detail.
- **AC3** — Given this view, Then it is **distinct from the per-run Run-Review screen** (C6) — historical/cross-run vs. single-run moderation — while reusing shared components.
- **AC4** — Given the DE team consumes the database directly, Then audit tables are part of the validated dataset they read (no separate export needed).

#### D6 — Audit integrity & retention
- **AC1** — Given any audit record (`ingestion_runs`, `audit_event`, prior-value history), Then it is **append-only** — not editable or deletable through the application.
- **AC2** — ⚠ Retention policy for audit records and any retained file copies is defined before go-live (§8 parked).

## §7 — Epic E: Admin Auth & RBAC (trimmed)

This epic is mostly **subtraction** from an already-built, already-tested v1 auth system. Validata v2 has exactly **one in-app role: `ADMIN`**.

### 7.1 Role model

- ✅ **Single role: `ADMIN`.** The `UserRole` enum drops **`SUPER_ADMIN`** (decided) and **`INSTRUCTOR`** (instructors are no longer users — §2.5, §2.1).
- ✅ `users` table is **admins-only**. Instructors live in `instructor_contacts` (passwordless).
- Because there is one role, **RBAC collapses** from role-differentiation (admin-vs-instructor routes, cross-role `/403`) to **authenticated-admin vs. not**.

### 7.2 Retained (admin-only — carried from v1)

Login (JWT + refresh), forced password change on first login (`MustChangePasswordFilter`), forgot/reset password, token refresh, logout / server-side invalidation, route + API auth guards.

### 7.3 Removed

All instructor auth: login, forced-change, provisioning (`ProvisionInstructorRequest/Response`, `InstructorProvisionedEvent`, welcome email), the `INSTRUCTOR` role, and the `SUPER_ADMIN` role — plus the FE instructor auth surfaces (set-password-as-instructor, etc.).

### 7.4 Stories & Acceptance Criteria

#### E1 — Admin login
- **AC1** — Given valid admin credentials, When I log in, Then a JWT (+ refresh) is issued and I land on the admin dashboard.
- **AC2** — Given wrong password or unknown email, Then login is rejected `401` with a generic message (no account enumeration).
- **AC3** — Given a deactivated admin (`is_active = false`), Then login is rejected `403`.

#### E2 — Forced first-login password change
- **AC1** — Given an admin with `must_change_password = true`, When authenticated, Then all routes redirect to set-password until it is changed (`MustChangePasswordFilter`).
- **AC2** — Given a successful change, Then `must_change_password` clears and normal access resumes.
- **AC3** — Given the new password equals the current, Then it is rejected.

#### E3 — Forgot / reset password
- **AC1** — Given forgot-password for any email (known or not), Then the same success message shows (no enumeration).
- **AC2** — Given a valid, unexpired reset token, Then reset succeeds and the token is invalidated (single-use).
- **AC3** — Given an expired or reused token, Then reset is rejected.

#### E4 — Session / token lifecycle
- **AC1** — Given an expired access token with a valid refresh token, Then the session refreshes silently.
- **AC2** — Given both tokens expired, Then the user is redirected to login.
- **AC3** — Given logout, Then tokens are invalidated server-side.

#### E5 — RBAC enforcement (single-role)
- **AC1** — Given an unauthenticated request to any `/admin/*` route or protected API, Then it is rejected (redirect to login / `401`).
- **AC2** — Given a request with a tampered or invalid JWT, Then it is rejected `403`/`401`.
- **AC3** — Given there is only the `ADMIN` role, Then no cross-role `/403` differentiation logic remains — any authenticated admin may access all admin surfaces.

#### E6 — Single-role schema (greenfield)
- **AC1** — Given the greenfield rebuild (D-6), Then the fresh schema defines a **single `ADMIN` role** (no `SUPER_ADMIN`/`INSTRUCTOR` enum values) and instructors exist only in `instructor_contacts` — built new, not migrated.
- **AC2** — Given retained auth is **ported from the `v1` branch**, Then instructor-provisioning endpoints, `InstructorProvisionedEvent`/welcome email, and instructor auth surfaces are simply not carried over (nothing to delete).

---

## §8 — Open Decisions & Risk Register

### 8.1 Open decisions (need a call before/with implementation)

| ID | Decision | Owner | Status |
|---|---|---|---|
| **G4** | Gate-4 provenance | Team | ✅ **DECIDED — validation route, read-only.** Validata does not own/generate sheets; it validates what the admin supplies (§3.3 A7) |
| **D-2** | Collision handling (re-grade vs duplicate) | Team | ✅ **DECIDED — changed = upsert + notify; in-file duplicate = conflict queue** (§2.4, §4.3) |
| **D-3** | Sync idempotency / change detection | Team | ✅ **DECIDED — file `quickXorHash` short-circuit + DB-as-processed-state row classification via `(learner_id,lab_id)` + value-hash** (§4.3). No write-back |
| **D-READ** | Read layer: workbook API vs download+parse | Team | ✅ **DECIDED — download content + parse locally (Apache POI); Graph Excel API not used** (§4.1) |
| **D-HASH** | Value-hash scope | Team | ✅ **DECIDED — Total Score + Review Date** (Instructor excluded) (§4.3) |
| **Q11** | Skip-invalid vs stop-file | Team | ✅ **DECIDED — skip-and-continue, graduated fail-scope; loud alert at >50%** (§4.5) |
| **D-6** | Migration approach | Team | ✅ **DECIDED — greenfield rebuild.** `dev` archived to a `v1` branch; new `dev` built fresh, porting only retained modules from v1. No in-place migration |
| **D-LIT** | Fixed literals: 2 folder names, 5 reference file names + shapes, link format | 📣 PO / IT | 🚫 OPEN — blocks Gates 2–4 |
| **D-ROUND** | Score rounding rule for ×100 conversion | Team | ⚠ OPEN — minor |
| **D-TRIG** | Trigger: scheduled + manual (Decision Log Q2, rec. C) + timezone/DST | 📣 PO | ⚠ OPEN — provisional default in B1 |
| **D-CRED** | Azure AD / Graph credentials scope | IT | ✅ **`Sites.Read.All` (read-only)** — no write-back anywhere |
| **D-NOTIF** | Notification model | Team | ✅ **DECIDED — staged outbox + admin moderation; instructor emails HELD by default; auto for internal/action alerts** (§5) |
| **Q3** | Notification format sign-off | 📣 PO | ⚠ OPEN — provisional per proposal examples (§5.4 C11) |
| **D-AUDIT** | Audit model | Team | ✅ **DECIDED — extend `csv_uploads`→`ingestion_runs` (new counts, `triggered_by`, `SKIPPED`); new `audit_event` store for lifecycle; reuse `LabReferenceAuditLog` for prior values; append-only** (§6) |
| **D-ROLE** | Auth role model | Team | ✅ **DECIDED — single `ADMIN` role; drop `SUPER_ADMIN` + `INSTRUCTOR`; `users` admins-only; instructors → `instructor_contacts`** (§7) |

*(Credentials provisioned and file access confirmed. Read-only is now firm across both epics — Gate 4 validates and Epic B downloads, neither writes.)*

### 8.2 Risk register

| ID | Risk | Impact | Disposition |
|---|---|---|---|
| **R-1** | **Frozen reference vs. mid-cohort growth/churn** — real programs add modules/labs and gain/lose learners after stand-up; the frozen model can't absorb this in-app | High | ✅ **DECIDED — frozen from the start; mid-cohort additions are not accounted for in v2.** Documented as a known limitation of the current flow. A mid-cohort change requires discard/reset + re-stand-up. Revisit in a future version if the workflow demands it |
| **R-5a** | Graph **token expiry** mid-job | Med | Team to decide refresh strategy |
| **R-5b** | Graph **throttling / 429** on Monday sync | Med | Retry/backoff design (Epic B) |
| **R-5c** | Score file **open/locked** in Excel Online during read | Low | Mitigated by download+parse (D-READ) — Graph returns last-saved bytes; no live-session dependency |
| **R-5d** | Folder **moved/renamed/permissions revoked after** successful stand-up | High | Runtime re-validation before each sync |
| **R-6** | ~~Migration/cutover + dead-code removal~~ — **retired** by the greenfield decision (D-6): no in-place migration, no dead-code removal; instructor UI/CSV endpoints are simply not ported from v1 | Low | Closed |
| **R-9** | Admin-supplied link could target unsanctioned location | Med | Constrain to tenant site; validate at Gate 1 |
| **R-10** | **Apache POI re-introduced** (reverses v2 proposal's ~1.5-day saving): in-memory workbook parsing + untrusted-`.xlsx` surface (OOXML XXE, zip bombs) | Med | Accepted for reliability + testability. Cap workbook size; enable POI hardening; our sheets are small. Effort estimate to be revised upward |

### 8.3 Parked (decide later)

Link security hardening details · full audit field list · **QA/test strategy for SharePoint (test tenant vs. mockable Graph layer)** · cron observability/alerting · timezone/DST around scheduled run · notification bounce handling · rebrand consistency (repos still named `lab-results-validation-app`) · cohort end-of-life/archival.

---

## §9 — Acceptance-Criteria Conventions (for Jira translation)

- **Epic** = each `§3`/`§4`… top-level feature area.
- **Story** = each `Ax` / `Bx` item; title mirrors the "As a … I …" line.
- **Sub-task** = individual **AC** bullets (and nested bullets) — each is independently verifiable.
- **Format:** every AC is **Given / When / Then**, phrased so it doubles as a test case (QA and spec converge — the new QA approach).
- **Definition of Done (per story):** all ACs pass + audit entry (where applicable) + no regression in retained suites.
- **Open items** (`⚠`/`🚫`/`📣`) become Jira tickets in a **Decisions** epic, linked as blockers to the stories they gate.
