-- Replace the native PostgreSQL enum type with VARCHAR + CHECK constraint.
-- The learner_status enum type caused JDBC type-mismatch errors because
-- Hibernate binds String values as character varying, and PostgreSQL has no
-- implicit cast from character varying to a custom enum type.

-- Drop the dependent view so the enum type has no remaining references
DROP VIEW IF EXISTS reporting.vw_learners;

-- USING clause is required for PostgreSQL to cast enum → varchar
ALTER TABLE learners
    ALTER COLUMN status TYPE VARCHAR(20) USING status::text;

ALTER TABLE learners
    ALTER COLUMN status SET DEFAULT 'active';

ALTER TABLE learners
    ADD CONSTRAINT chk_learner_status CHECK (status IN ('active', 'archived'));

DROP TYPE learner_status;

-- Recreate the view against the new VARCHAR column
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
