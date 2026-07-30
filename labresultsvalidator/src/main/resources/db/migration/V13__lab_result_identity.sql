-- ============================================================
-- Row identity for lab_results changes from (learner_id, lab_id) to
-- (submitted_on, nsp_name). Classification/change-detection for the grading
-- ingestion pipeline (Epic B) matches rows on review date + the raw
-- "Name of NSP" text from the score sheet, not the resolved learner/lab
-- foreign keys (those are kept on the table for reporting/joins only).
--
-- Table has never been written to by any code path, so this is a plain
-- additive change, not a backfill.
-- ============================================================

ALTER TABLE lab_results ADD COLUMN nsp_name VARCHAR(255) NOT NULL;

ALTER TABLE lab_results DROP CONSTRAINT uq_lab_result;

ALTER TABLE lab_results ADD CONSTRAINT uq_lab_result UNIQUE (submitted_on, nsp_name);
