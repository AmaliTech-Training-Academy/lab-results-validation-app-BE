-- ============================================================
-- Module phase/sequence becomes optional AND text, not a strict integer
-- column, at reference-data stand-up time.
--
-- Optional: specializations get their modules added incrementally (a
-- curriculum in progress may only have its first module defined so far) —
-- requiring every module to carry a phase number the moment it's added was
-- too rigid for that rollout. sequence still means "the Nth module within
-- this specialization" and is still required for a "Module-<phase>" grading
-- sheet to resolve (Epic B) — it just no longer has to be assigned upfront.
--
-- Text: stored as the reference file's phase value directly rather than a
-- parsed integer, so the column no longer dictates a strict numeric shape
-- at the database layer. Validation of what a non-blank value must look
-- like now lives entirely in application code (Gate 3), not a DB CHECK.
-- ============================================================

ALTER TABLE modules DROP CONSTRAINT modules_sequence_check;
ALTER TABLE modules ALTER COLUMN sequence DROP NOT NULL;
ALTER TABLE modules ALTER COLUMN sequence TYPE VARCHAR(20);
