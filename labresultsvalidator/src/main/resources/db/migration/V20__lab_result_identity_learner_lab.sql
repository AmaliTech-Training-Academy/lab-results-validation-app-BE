-- ============================================================
-- Row identity for lab_results reverts from (submitted_on, nsp_name) back to
-- (learner_id, lab_id) — see V13__lab_result_identity.sql for the change this
-- undoes.
--
-- The (submitted_on, nsp_name) key broke on two fronts: submitted_on is one of
-- the fields a re-grade legitimately changes, so a re-grade entered on a new
-- date no longer matched its prior row (silently rejected as a duplicate);
-- and two different labs graded for the same trainee on the same day
-- collided, since the lab was never part of the key. learner_id/lab_id are
-- resolved during validation and don't change between ingestion runs, so
-- they're the stable identity; submitted_on + score are still used for
-- change detection (RowFingerprint), just not for row identity.
-- ============================================================

ALTER TABLE lab_results DROP CONSTRAINT uq_lab_result;

ALTER TABLE lab_results ADD CONSTRAINT uq_lab_result UNIQUE (learner_id, lab_id);
