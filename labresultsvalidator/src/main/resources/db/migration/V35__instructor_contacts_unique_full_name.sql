-- ============================================================
-- Root-cause fix for the instructor-contact duplication bug: reference-data
-- commits used to upsert InstructorContact by email (see V29's comment,
-- now stale), so the same instructor recurring across cohorts with a
-- slightly different email (typo, personal vs. work address, re-entry)
-- got a second row under the same full_name. The weekly grading sync
-- resolves a sheet's Reviewer column by full_name
-- (InstructorContactRepository.findByFullNameIgnoreCase) expecting exactly
-- one match — two rows with the same name make that lookup throw instead
-- of resolving, and whichever duplicate happens to be picked up elsewhere
-- misdirects instructor emails. ReferenceCommitService.persistInstructors
-- now upserts by full_name instead of email; this migration folds any
-- dupes already created under the old behavior into one row per name, then
-- enforces it at the DB level so it can't recur.
-- ============================================================

-- One keeper per LOWER(full_name): the oldest row, ties broken by id for
-- determinism. Everything else is a duplicate to fold into it.
CREATE TEMP TABLE instructor_dupe_map AS
SELECT ic.id AS dupe_id, keeper.id AS keeper_id
FROM instructor_contacts ic
JOIN (
    SELECT DISTINCT ON (LOWER(full_name)) id, full_name
    FROM instructor_contacts
    ORDER BY LOWER(full_name), created_at, id
) keeper ON LOWER(keeper.full_name) = LOWER(ic.full_name)
WHERE ic.id <> keeper.id;

-- Repoint FKs that tolerate a plain UPDATE (nothing else constrains them).
UPDATE lab_results lr
SET instructor_contact_id = m.keeper_id
FROM instructor_dupe_map m
WHERE lr.instructor_contact_id = m.dupe_id;

UPDATE notifications n
SET recipient_instructor_id = m.keeper_id
FROM instructor_dupe_map m
WHERE n.recipient_instructor_id = m.dupe_id;

-- instructor_specialization_assignments has UNIQUE(instructor_contact_id, specialization_id):
-- drop a duplicate's assignment outright when the keeper already has one for that same
-- specialization, otherwise repoint it.
DELETE FROM instructor_specialization_assignments isa
USING instructor_dupe_map m
WHERE isa.instructor_contact_id = m.dupe_id
  AND EXISTS (
      SELECT 1 FROM instructor_specialization_assignments keep
      WHERE keep.instructor_contact_id = m.keeper_id
        AND keep.specialization_id = isa.specialization_id
  );

UPDATE instructor_specialization_assignments isa
SET instructor_contact_id = m.keeper_id
FROM instructor_dupe_map m
WHERE isa.instructor_contact_id = m.dupe_id;

DELETE FROM instructor_contacts ic
USING instructor_dupe_map m
WHERE ic.id = m.dupe_id;

DROP TABLE instructor_dupe_map;

-- Enforce the real identity going forward — mirrors idx_instructor_email's
-- LOWER(...) pattern from V1.
CREATE UNIQUE INDEX uq_instructor_contacts_full_name ON instructor_contacts (LOWER(full_name));
