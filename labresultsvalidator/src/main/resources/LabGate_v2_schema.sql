-- ============================================================
-- LabGate v2 — SharePoint Grading Integration
-- PostgreSQL Database Schema (GREENFIELD)
-- Target: fresh `dev` branch V1__create_tables.sql
-- Date: 2026-07-23
-- ============================================================
-- Design decisions baked in (PRD §2, §8):
--   * Ingestion source = SharePoint via Graph API (no manual CSV upload)
--   * Single ADMIN role; instructors are passwordless contact records
--   * LearnerID + InstructorID + module.code are the match keys
--   * max_score fixed at 100 (kept as a defaulted column, not dropped)
--   * Single result per (learner, lab) — attempt_number retired
--   * Reference data frozen after Accept (no in-app edit / force-edit)
--   * Reporting views REMOVED — Data Engineering owns reporting and
--     consumes these tables directly (PRD §0.3)
--   * VARCHAR + CHECK for all state columns (NOT native ENUM — v1
--     migrations V5–V8 removed native enums due to JDBC cast errors)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- 1. users  — ADMINS ONLY (single role)
-- ============================================================
CREATE TABLE users (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                VARCHAR(254) NOT NULL UNIQUE,
    password_hash        TEXT         NOT NULL,
    role                 VARCHAR(20)  NOT NULL DEFAULT 'admin',
    is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by           UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by           UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_user_role CHECK (role = 'admin')   -- single role for v2
);

-- ============================================================
-- 2. instructor_contacts  — passwordless notification targets
-- Populated from the reference bundle at stand-up. NOT login users.
-- ============================================================
CREATE TABLE instructor_contacts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    instructor_id VARCHAR(50)  NOT NULL UNIQUE,          -- e.g. INS-001
    email         VARCHAR(254) NOT NULL UNIQUE,
    full_name     VARCHAR(200) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by    UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by    UUID         REFERENCES users(id) ON DELETE SET NULL
);

-- ============================================================
-- 3. cohorts  — carries the stand-up lifecycle + SharePoint link
-- ============================================================
CREATE TABLE cohorts (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(150) NOT NULL UNIQUE,
    start_date             DATE         NOT NULL,
    end_date               DATE         NOT NULL,
    lifecycle_state        VARCHAR(30)  NOT NULL DEFAULT 'DRAFT',
    is_locked              BOOLEAN      NOT NULL DEFAULT FALSE,   -- orthogonal lock; only meaningful once STOOD_UP
    is_active              BOOLEAN      NOT NULL DEFAULT TRUE,
    sharepoint_folder_url  TEXT,                                  -- canonical link the admin provides
    sharepoint_drive_id    VARCHAR(200),                          -- resolved by Graph at Gate 1 (nullable until resolved)
    sharepoint_item_id     VARCHAR(200),
    reference_accepted_at  TIMESTAMPTZ,                           -- checkpoint convenience (full detail in audit_event)
    reference_accepted_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by             UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by             UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_cohort_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_cohort_state CHECK (lifecycle_state IN ('DRAFT','REFERENCE_ACCEPTED','STOOD_UP'))
);

-- ============================================================
-- 4. specializations
-- ============================================================
CREATE TABLE specializations (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id   UUID         NOT NULL REFERENCES cohorts(id) ON DELETE RESTRICT,
    name        VARCHAR(150) NOT NULL,
    code        VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_specialization_name UNIQUE (cohort_id, name),
    CONSTRAINT uq_specialization_code UNIQUE (cohort_id, code)
);

-- ============================================================
-- 5. modules  — `code` is the sheet-name → module lookup key (NEW)
-- NOTE: code is unique within specialization. The sheet→module lookup
-- resolves within a (cohort, specialization) context. If the D-LIT file
-- layout turns out NOT to be specialization-scoped (one workbook spanning
-- specializations), revisit to enforce uniqueness within cohort instead.
-- ============================================================
CREATE TABLE modules (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    specialization_id UUID         NOT NULL REFERENCES specializations(id) ON DELETE RESTRICT,
    name              VARCHAR(150) NOT NULL,
    code              VARCHAR(20)  NOT NULL,             -- e.g. BEM01
    sequence          INT          NOT NULL CHECK (sequence > 0),
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_module_name     UNIQUE (specialization_id, name),
    CONSTRAINT uq_module_code     UNIQUE (specialization_id, code),
    CONSTRAINT uq_module_sequence UNIQUE (specialization_id, sequence),
    CONSTRAINT chk_module_status  CHECK (status IN ('active','archived'))
);

-- ============================================================
-- 6. labs  — max_score fixed at 100 (kept, defaulted). No immutability.
-- ============================================================
CREATE TABLE labs (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id  UUID          NOT NULL REFERENCES modules(id) ON DELETE RESTRICT,
    title      VARCHAR(200)  NOT NULL,
    max_score  NUMERIC(8,2)  NOT NULL DEFAULT 100 CHECK (max_score > 0),
    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by UUID          REFERENCES users(id) ON DELETE SET NULL,
    updated_by UUID          REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_lab_title UNIQUE (module_id, title)
);

-- ============================================================
-- 7. learners  — learner_id is the primary match key (NEW); email fallback
-- ============================================================
CREATE TABLE learners (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    learner_id        VARCHAR(50)  NOT NULL UNIQUE,       -- e.g. DEG-2026-001
    full_name         VARCHAR(200) NOT NULL,
    email             VARCHAR(254) NOT NULL UNIQUE,        -- secondary / fallback identifier
    cohort_id         UUID         NOT NULL REFERENCES cohorts(id) ON DELETE RESTRICT,
    specialization_id UUID         NOT NULL REFERENCES specializations(id) ON DELETE RESTRICT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    updated_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT chk_learner_status CHECK (status IN ('active','archived'))
);

-- ============================================================
-- 8. ingestion_runs  (was csv_uploads) — one record per file per run
-- file hash is NOT unique: every run writes a record (incl. SKIPPED when
-- the file is unchanged). Dedup is application logic (compare hash to the
-- last run for this file), NOT a DB uniqueness constraint.
-- ============================================================
CREATE TABLE ingestion_runs (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cohort_id             UUID          NOT NULL REFERENCES cohorts(id) ON DELETE RESTRICT,
    workbook_filename     VARCHAR(255)  NOT NULL,
    sharepoint_file_url   TEXT,
    sharepoint_version_id VARCHAR(200),
    quick_xor_hash        VARCHAR(128),                    -- Graph file.hashes.quickXorHash
    file_sha256           CHAR(64),                        -- optional secondary hash
    triggered_by          UUID          REFERENCES users(id) ON DELETE SET NULL,  -- NULL = SYSTEM (scheduled)
    trigger_type          VARCHAR(20)   NOT NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'processing',
    rows_read             INT           NOT NULL DEFAULT 0 CHECK (rows_read         >= 0),
    committed_new         INT           NOT NULL DEFAULT 0 CHECK (committed_new     >= 0),
    updated_count         INT           NOT NULL DEFAULT 0 CHECK (updated_count     >= 0),
    skipped_invalid       INT           NOT NULL DEFAULT 0 CHECK (skipped_invalid   >= 0),
    skipped_unchanged     INT           NOT NULL DEFAULT 0 CHECK (skipped_unchanged >= 0),
    conflicts_count       INT           NOT NULL DEFAULT 0 CHECK (conflicts_count   >= 0),
    error_report_json     JSONB,
    run_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_trigger_type CHECK (trigger_type IN ('SCHEDULED','MANUAL')),
    CONSTRAINT chk_run_status   CHECK (status IN ('processing','completed','partial','failed','skipped'))
);

-- ============================================================
-- 9. lab_results  — one result per (learner, lab). No attempt_number.
-- instructor_contact_id replaces free-text graded_by (notification link).
-- row_value_hash powers change detection (hash of score + submitted_on).
-- ============================================================
CREATE TABLE lab_results (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    learner_id            UUID          NOT NULL REFERENCES learners(id)            ON DELETE RESTRICT,
    lab_id                UUID          NOT NULL REFERENCES labs(id)                ON DELETE RESTRICT,
    ingestion_run_id      UUID          NOT NULL REFERENCES ingestion_runs(id)      ON DELETE RESTRICT,
    instructor_contact_id UUID          REFERENCES instructor_contacts(id)          ON DELETE SET NULL,
    score                 NUMERIC(8,2)  NOT NULL CHECK (score >= 0),
    max_score_snapshot    NUMERIC(8,2)  NOT NULL DEFAULT 100 CHECK (max_score_snapshot > 0),
    submitted_on          DATE          NOT NULL,          -- Review Date
    row_value_hash        VARCHAR(64)   NOT NULL,          -- change-detection fingerprint
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by            UUID          REFERENCES users(id) ON DELETE SET NULL,
    updated_by            UUID          REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_lab_result UNIQUE (learner_id, lab_id),
    CONSTRAINT chk_score_max CHECK (score <= max_score_snapshot)
);

-- ============================================================
-- 10. lab_reference_audit_log  — prior-value history (reused)
-- Home for changed-row (re-grade) prior values: table_name='lab_results',
-- field_name='score', old_value/new_value. Also any reference forensics.
-- ============================================================
CREATE TABLE lab_reference_audit_log (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    table_name         VARCHAR(100) NOT NULL,
    record_id          UUID         NOT NULL,
    field_name         VARCHAR(100) NOT NULL,
    old_value          TEXT,
    new_value          TEXT,
    changed_by         UUID         REFERENCES users(id) ON DELETE SET NULL,
    deleted_user_email VARCHAR(254),
    changed_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    reason             TEXT
);

-- ============================================================
-- 11. audit_event  — cohort / stand-up lifecycle events (NEW)
-- ============================================================
CREATE TABLE audit_event (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type    VARCHAR(40)  NOT NULL,
    cohort_id     UUID         REFERENCES cohorts(id) ON DELETE SET NULL,
    actor_user_id UUID         REFERENCES users(id)   ON DELETE SET NULL,  -- NULL = SYSTEM
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    payload_json  JSONB,
    CONSTRAINT chk_audit_event_type CHECK (event_type IN (
        'LINK_SUBMITTED','GATE_FAILED','GATE_PASSED','REFERENCE_ACCEPTED',
        'DISCARD_RESET','COHORT_LOCKED','COHORT_UNLOCKED','STOOD_UP','CONFLICT_RESOLVED'
    ))
);

-- ============================================================
-- 12. notifications  — staged outbox with admin moderation (NEW)
-- ============================================================
CREATE TABLE notifications (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ingestion_run_id        UUID         REFERENCES ingestion_runs(id) ON DELETE CASCADE,
    cohort_id               UUID         REFERENCES cohorts(id)        ON DELETE SET NULL,
    type                    VARCHAR(40)  NOT NULL,
    recipient_kind          VARCHAR(20)  NOT NULL,
    recipient_instructor_id UUID         REFERENCES instructor_contacts(id) ON DELETE SET NULL,
    recipient_user_id       UUID         REFERENCES users(id)               ON DELETE SET NULL,
    dispatch_policy         VARCHAR(10)  NOT NULL DEFAULT 'HELD',
    subject                 TEXT,
    body                    TEXT,
    payload_json            JSONB,
    status                  VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    error_detail            TEXT,
    sent_by                 UUID         REFERENCES users(id) ON DELETE SET NULL,
    sent_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_notif_type   CHECK (type IN (
        'instructor_digest','admin_run_digest','standup_failure',
        'high_failure','conflict_alert','stood_up')),
    CONSTRAINT chk_notif_kind   CHECK (recipient_kind IN ('instructor','admin')),
    CONSTRAINT chk_notif_policy CHECK (dispatch_policy IN ('AUTO','HELD')),
    CONSTRAINT chk_notif_status CHECK (status IN ('PENDING','SENT','SKIPPED','FAILED'))
);

-- ============================================================
-- 13. ingestion_conflicts  — conflict queue, persists until resolved (NEW)
-- ============================================================
CREATE TABLE ingestion_conflicts (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ingestion_run_id   UUID         NOT NULL REFERENCES ingestion_runs(id) ON DELETE CASCADE,
    cohort_id          UUID         NOT NULL REFERENCES cohorts(id)        ON DELETE RESTRICT,
    learner_id         UUID         REFERENCES learners(id) ON DELETE SET NULL,  -- may be unresolved
    lab_id             UUID         REFERENCES labs(id)     ON DELETE SET NULL,
    conflict_kind      VARCHAR(30)  NOT NULL DEFAULT 'in_file_duplicate',
    existing_result_id UUID         REFERENCES lab_results(id) ON DELETE SET NULL,
    incoming_payload_json JSONB     NOT NULL,               -- the conflicting incoming rows
    status             VARCHAR(15)  NOT NULL DEFAULT 'PENDING',
    resolved_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
    resolved_at        TIMESTAMPTZ,
    resolution_note    TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_conflict_kind   CHECK (conflict_kind IN ('in_file_duplicate')),
    CONSTRAINT chk_conflict_status CHECK (status IN ('PENDING','RESOLVED','DISMISSED'))
);

-- ============================================================
-- INDEXES
-- ============================================================
CREATE INDEX idx_instructor_email       ON instructor_contacts (LOWER(email));
CREATE INDEX idx_cohorts_state          ON cohorts (lifecycle_state);
CREATE INDEX idx_learners_email         ON learners (LOWER(email));
CREATE INDEX idx_learners_learnerid     ON learners (learner_id);
CREATE INDEX idx_learners_cohort        ON learners (cohort_id);
CREATE INDEX idx_learners_spec          ON learners (specialization_id);
CREATE INDEX idx_modules_spec_code      ON modules (specialization_id, code);
CREATE INDEX idx_modules_spec_seq       ON modules (specialization_id, sequence);
CREATE INDEX idx_labs_module_title      ON labs (module_id, LOWER(title));   -- case-insensitive lab-title match
CREATE INDEX idx_runs_cohort_time       ON ingestion_runs (cohort_id, run_at DESC);
CREATE INDEX idx_runs_hash              ON ingestion_runs (quick_xor_hash);  -- last-run lookup for dedup
CREATE INDEX idx_results_learner        ON lab_results (learner_id);
CREATE INDEX idx_results_lab            ON lab_results (lab_id);
CREATE INDEX idx_results_run            ON lab_results (ingestion_run_id);
CREATE INDEX idx_results_instructor     ON lab_results (instructor_contact_id);
CREATE INDEX idx_audit_ref_record       ON lab_reference_audit_log (table_name, record_id, changed_at DESC);
CREATE INDEX idx_audit_event_cohort     ON audit_event (cohort_id, occurred_at DESC);
CREATE INDEX idx_audit_event_type       ON audit_event (event_type);
CREATE INDEX idx_notifications_run      ON notifications (ingestion_run_id);
CREATE INDEX idx_notifications_status   ON notifications (status);
CREATE INDEX idx_conflicts_run          ON ingestion_conflicts (ingestion_run_id);
CREATE INDEX idx_conflicts_status       ON ingestion_conflicts (status);

-- ============================================================
-- TRIGGER: set_updated_at (kept). Immutability + max_score_snapshot
-- enforcement triggers from v1 are REMOVED:
--   * enforce_lab_immutability  → reference data is frozen; no in-app edits
--   * enforce_max_score_snapshot → max_score is a fixed 100; app sets snapshot
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_updated_at               BEFORE UPDATE ON users               FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_instructor_contacts_updated_at BEFORE UPDATE ON instructor_contacts FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_cohorts_updated_at             BEFORE UPDATE ON cohorts             FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_specializations_updated_at     BEFORE UPDATE ON specializations     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_modules_updated_at             BEFORE UPDATE ON modules             FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_labs_updated_at                BEFORE UPDATE ON labs                FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_learners_updated_at            BEFORE UPDATE ON learners            FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_ingestion_runs_updated_at      BEFORE UPDATE ON ingestion_runs      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_lab_results_updated_at         BEFORE UPDATE ON lab_results         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_notifications_updated_at       BEFORE UPDATE ON notifications       FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_conflicts_updated_at           BEFORE UPDATE ON ingestion_conflicts FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================
-- REPORTING: intentionally OMITTED (PRD §0.3).
-- Data Engineering owns the reporting layer and consumes these operational
-- tables directly. Provide a read-only role to them out-of-band if needed.
-- ============================================================

-- ============================================================
-- END OF SCHEMA (LabGate v2, greenfield)
-- ============================================================