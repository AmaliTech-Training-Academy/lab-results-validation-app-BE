-- ============================================================
-- instructor_specialization_assignments: links a global InstructorContact to a cohort-scoped
-- specialization. Follows the same delete-then-recreate-per-commit lifecycle as
-- specializations/modules/labs/learners (see ReferenceCommitService.clearPreviousReferenceData),
-- while instructor_contacts itself stays a global, never-deleted, upsert-by-email table.
-- ============================================================
CREATE TABLE instructor_specialization_assignments (
    id                     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    instructor_contact_id  UUID        NOT NULL REFERENCES instructor_contacts(id) ON DELETE CASCADE,
    specialization_id      UUID        NOT NULL REFERENCES specializations(id)     ON DELETE CASCADE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by             UUID        REFERENCES users(id) ON DELETE SET NULL,
    updated_by             UUID        REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT uq_instructor_specialization UNIQUE (instructor_contact_id, specialization_id)
);

CREATE INDEX idx_instructor_spec_assignments_instructor ON instructor_specialization_assignments (instructor_contact_id);
CREATE INDEX idx_instructor_spec_assignments_spec       ON instructor_specialization_assignments (specialization_id);

CREATE TRIGGER trg_instructor_spec_assignments_updated_at
    BEFORE UPDATE ON instructor_specialization_assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();