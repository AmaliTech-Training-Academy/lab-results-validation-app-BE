-- ============================================================
-- LabGate — Lab Results Validation App
-- PostgreSQL Database Schema
-- Version 1.1 | June 2026
-- ============================================================

-- Enable UUID generation
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- ENUMS
-- ============================================================

CREATE TYPE learner_status AS ENUM ('active', 'archived');
CREATE TYPE user_role      AS ENUM ('admin', 'instructor');
CREATE TYPE upload_status  AS ENUM ('processing', 'completed', 'failed');

-- ============================================================
-- OPERATIONAL SCHEMA TABLES
-- (ordered so every table is defined before anything references it)
-- ============================================================

-- ------------------------------------------------------------
-- 1. users  — no dependencies; must come first
-- ------------------------------------------------------------
CREATE TABLE users (
                       id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                       email                VARCHAR(254) NOT NULL UNIQUE,
                       password_hash        TEXT         NOT NULL,
                       role                 user_role    NOT NULL,
                       is_active            BOOLEAN      NOT NULL DEFAULT TRUE,
                       must_change_password BOOLEAN      NOT NULL DEFAULT TRUE,
                       created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                       updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                       created_by           UUID         REFERENCES users(id) ON DELETE SET NULL,
                       updated_by           UUID         REFERENCES users(id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 2. cohorts  — references users
-- ------------------------------------------------------------
CREATE TABLE cohorts (
                         id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                         name         VARCHAR(150) NOT NULL UNIQUE,
                         start_date   DATE         NOT NULL,
                         end_date     DATE         NOT NULL,
                         is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
                         created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                         updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                         created_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
                         updated_by   UUID         REFERENCES users(id) ON DELETE SET NULL,
                         CONSTRAINT chk_cohort_dates CHECK (end_date >= start_date)
);

-- ------------------------------------------------------------
-- 3. specializations  — references cohorts, users
-- ------------------------------------------------------------
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

-- ------------------------------------------------------------
-- 4. modules  — references specializations, users
-- ------------------------------------------------------------
CREATE TABLE modules (
                         id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                         specialization_id UUID         NOT NULL REFERENCES specializations(id) ON DELETE RESTRICT,
                         name              VARCHAR(150) NOT NULL,
                         sequence          INT          NOT NULL CHECK (sequence > 0),
                         created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                         updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                         created_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
                         updated_by        UUID         REFERENCES users(id) ON DELETE SET NULL,
                         CONSTRAINT uq_module_name     UNIQUE (specialization_id, name),
                         CONSTRAINT uq_module_sequence UNIQUE (specialization_id, sequence)
);

-- ------------------------------------------------------------
-- 5. labs  — references modules, users
-- ------------------------------------------------------------
CREATE TABLE labs (
                      id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                      module_id    UUID          NOT NULL REFERENCES modules(id) ON DELETE RESTRICT,
                      title        VARCHAR(200)  NOT NULL,
                      max_score    NUMERIC(8,2)  NOT NULL CHECK (max_score > 0),
                      is_immutable BOOLEAN       NOT NULL DEFAULT FALSE,
                      created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                      updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                      created_by   UUID          REFERENCES users(id) ON DELETE SET NULL,
                      updated_by   UUID          REFERENCES users(id) ON DELETE SET NULL,
                      CONSTRAINT uq_lab_title UNIQUE (module_id, title)
);

-- ------------------------------------------------------------
-- 6. learners  — references cohorts, specializations, users
-- ------------------------------------------------------------
CREATE TABLE learners (
                          id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                          full_name         VARCHAR(200)   NOT NULL,
                          email             VARCHAR(254)   NOT NULL UNIQUE,
                          cohort_id         UUID           NOT NULL REFERENCES cohorts(id) ON DELETE RESTRICT,
                          specialization_id UUID           NOT NULL REFERENCES specializations(id) ON DELETE RESTRICT,
                          status            learner_status NOT NULL DEFAULT 'active',
                          created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
                          updated_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
                          created_by        UUID           REFERENCES users(id) ON DELETE SET NULL,
                          updated_by        UUID           REFERENCES users(id) ON DELETE SET NULL
);

-- ------------------------------------------------------------
-- 7. user_module_assignments  — references users, modules
-- Source of truth for validation rule V15 (instructor authorization).
-- Application code must query this table when scoping CSV uploads
-- and generating per-instructor CSV templates.
-- ------------------------------------------------------------
CREATE TABLE user_module_assignments (
                                         id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                                         user_id    UUID        NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
                                         module_id  UUID        NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
                                         created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                                         created_by UUID        REFERENCES users(id) ON DELETE SET NULL,
                                         CONSTRAINT uq_user_module UNIQUE (user_id, module_id)
);

-- ------------------------------------------------------------
-- 8. csv_uploads  — references users
-- file_sha256 is UNIQUE: enforces global file-level de-duplication.
-- Swap for UNIQUE (uploaded_by_user_id, file_sha256) if per-user dedup is preferred.
-- ------------------------------------------------------------
CREATE TABLE csv_uploads (
                             id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                             uploaded_by_user_id UUID          NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
                             filename            VARCHAR(255)  NOT NULL,
                             file_sha256         CHAR(64)      NOT NULL UNIQUE,
                             uploaded_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                             total_rows          INT           NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
                             accepted_rows       INT           NOT NULL DEFAULT 0 CHECK (accepted_rows >= 0),
                             rejected_rows       INT           NOT NULL DEFAULT 0 CHECK (rejected_rows >= 0),
                             status              upload_status NOT NULL DEFAULT 'processing',
                             error_report_json   JSONB,
                             created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                             updated_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                             CONSTRAINT chk_row_counts CHECK (accepted_rows + rejected_rows <= total_rows)
);

-- ------------------------------------------------------------
-- 9. lab_results  — references learners, labs, csv_uploads, users
-- max_score_snapshot integrity is enforced by trg_lab_results_snapshot_check.
-- ------------------------------------------------------------
CREATE TABLE lab_results (
                             id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
                             learner_id         UUID          NOT NULL REFERENCES learners(id)    ON DELETE RESTRICT,
                             lab_id             UUID          NOT NULL REFERENCES labs(id)        ON DELETE RESTRICT,
                             csv_upload_id      UUID          NOT NULL REFERENCES csv_uploads(id) ON DELETE RESTRICT,
                             score              NUMERIC(8,2)  NOT NULL CHECK (score >= 0),
                             max_score_snapshot NUMERIC(8,2)  NOT NULL CHECK (max_score_snapshot > 0),
                             attempt_number     SMALLINT      NOT NULL CHECK (attempt_number IN (1, 2)),
                             submitted_on       DATE          NOT NULL,
                             graded_by          VARCHAR(200),
                             created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                             updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
                             created_by         UUID          REFERENCES users(id) ON DELETE SET NULL,
                             updated_by         UUID          REFERENCES users(id) ON DELETE SET NULL,
                             CONSTRAINT uq_lab_result UNIQUE (learner_id, lab_id, attempt_number),
                             CONSTRAINT chk_score_max CHECK  (score <= max_score_snapshot)
);

-- ------------------------------------------------------------
-- 10. lab_reference_audit_log  — references users
-- changed_by is SET NULL so decommissioning an admin never locks this table.
-- deleted_user_email captures identity at write time for audit durability.
-- ------------------------------------------------------------
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
-- INDEXES
-- ============================================================

CREATE INDEX idx_learners_email     ON learners (LOWER(email));
CREATE INDEX idx_learners_cohort    ON learners (cohort_id);
CREATE INDEX idx_learners_spec      ON learners (specialization_id);
CREATE INDEX idx_labs_module_title  ON labs (module_id, LOWER(title));
CREATE INDEX idx_csv_uploads_user   ON csv_uploads (uploaded_by_user_id, uploaded_at DESC);
CREATE INDEX idx_lab_results_learner ON lab_results (learner_id);
CREATE INDEX idx_lab_results_lab    ON lab_results (lab_id);
CREATE INDEX idx_lab_results_upload ON lab_results (csv_upload_id);
CREATE INDEX idx_modules_spec_seq   ON modules (specialization_id, sequence);
CREATE INDEX idx_audit_log_record   ON lab_reference_audit_log (table_name, record_id, changed_at DESC);

-- ============================================================
-- TRIGGER FUNCTIONS
-- ============================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = NOW();
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_lab_immutability()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.is_immutable = TRUE THEN
        IF NEW.title <> OLD.title OR NEW.max_score <> OLD.max_score THEN
            RAISE EXCEPTION
                'Lab % is immutable. title and max_score cannot be changed directly. '
                'Use the admin force-edit flow (set is_immutable = FALSE first).',
                OLD.id;
END IF;
END IF;
RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION enforce_max_score_snapshot()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
configured_max NUMERIC(8,2);
BEGIN
SELECT max_score INTO configured_max
FROM   labs
WHERE  id = NEW.lab_id;

IF NEW.max_score_snapshot <> configured_max THEN
        RAISE EXCEPTION
            'max_score_snapshot (%) does not match the configured max_score (%) for lab %. '
            'Ensure the application sets max_score_snapshot = labs.max_score at insert time.',
            NEW.max_score_snapshot, configured_max, NEW.lab_id;
END IF;

RETURN NEW;
END;
$$;

-- ============================================================
-- TRIGGERS
-- ============================================================

CREATE TRIGGER trg_cohorts_updated_at
    BEFORE UPDATE ON cohorts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_specializations_updated_at
    BEFORE UPDATE ON specializations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_modules_updated_at
    BEFORE UPDATE ON modules
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_labs_updated_at
    BEFORE UPDATE ON labs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_learners_updated_at
    BEFORE UPDATE ON learners
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_csv_uploads_updated_at
    BEFORE UPDATE ON csv_uploads
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_lab_results_updated_at
    BEFORE UPDATE ON lab_results
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_labs_immutability
    BEFORE UPDATE ON labs
    FOR EACH ROW EXECUTE FUNCTION enforce_lab_immutability();

CREATE TRIGGER trg_lab_results_snapshot_check
    BEFORE INSERT ON lab_results
    FOR EACH ROW EXECUTE FUNCTION enforce_max_score_snapshot();

-- ============================================================
-- REPORTING SCHEMA & VIEWS  (read-only — for Power BI)
-- ============================================================

CREATE SCHEMA IF NOT EXISTS reporting;

CREATE OR REPLACE VIEW reporting.vw_lab_results_flat AS
SELECT
    lr.id                                               AS result_id,
    lr.submitted_on,
    lr.attempt_number,
    lr.score,
    lr.max_score_snapshot                               AS max_score,
    ROUND((lr.score / lr.max_score_snapshot) * 100, 2) AS score_pct,
    lr.graded_by,
    l.title                                             AS lab_title,
    m.name                                              AS module_name,
    m.sequence                                          AS module_sequence,
    sp.name                                             AS specialization_name,
    sp.code                                             AS specialization_code,
    co.name                                             AS cohort_name,
    co.start_date                                       AS cohort_start_date,
    co.end_date                                         AS cohort_end_date,
    le.full_name                                        AS learner_name,
    le.email                                            AS learner_email,
    lr.csv_upload_id,
    lr.created_at                                       AS result_created_at
FROM      lab_results     lr
              JOIN      learners        le ON le.id  = lr.learner_id
              JOIN      labs            l  ON l.id   = lr.lab_id
              JOIN      modules         m  ON m.id   = l.module_id
              JOIN      specializations sp ON sp.id  = m.specialization_id
              JOIN      cohorts         co ON co.id  = sp.cohort_id;

CREATE OR REPLACE VIEW reporting.vw_learners AS
SELECT
    le.id      AS learner_id,
    le.full_name,
    le.email,
    le.status,
    co.name    AS cohort_name,
    sp.name    AS specialization_name,
    sp.code    AS specialization_code
FROM      learners        le
              JOIN      cohorts         co ON co.id  = le.cohort_id
              JOIN      specializations sp ON sp.id  = le.specialization_id;

CREATE OR REPLACE VIEW reporting.vw_labs_catalog AS
SELECT
    l.id           AS lab_id,
    l.title        AS lab_title,
    l.max_score,
    l.is_immutable,
    m.name         AS module_name,
    m.sequence     AS module_sequence,
    sp.name        AS specialization_name,
    sp.code        AS specialization_code,
    co.name        AS cohort_name
FROM      labs            l
              JOIN      modules         m  ON m.id   = l.module_id
              JOIN      specializations sp ON sp.id  = m.specialization_id
              JOIN      cohorts         co ON co.id  = sp.cohort_id;

CREATE OR REPLACE VIEW reporting.vw_upload_audit AS
SELECT
    cu.id            AS upload_id,
    cu.filename,
    cu.file_sha256,
    cu.uploaded_at,
    cu.total_rows,
    cu.accepted_rows,
    cu.rejected_rows,
    cu.status,
    u.email          AS uploaded_by_email,
    u.role           AS uploaded_by_role
FROM  csv_uploads cu
          JOIN  users       u  ON u.id = cu.uploaded_by_user_id;

-- ============================================================
-- POWER BI READ-ONLY USER (run as superuser after migration)
-- ============================================================
-- CREATE ROLE powerbi_reader WITH LOGIN PASSWORD 'change_this_password';
-- GRANT USAGE  ON SCHEMA reporting TO powerbi_reader;
-- GRANT SELECT ON ALL TABLES IN SCHEMA reporting TO powerbi_reader;
-- ALTER DEFAULT PRIVILEGES IN SCHEMA reporting
--   GRANT SELECT ON TABLES TO powerbi_reader;

-- ============================================================
-- END OF SCHEMA  (v1.1)
-- ============================================================