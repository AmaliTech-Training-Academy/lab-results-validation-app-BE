-- ============================================================
-- Phase/sequence is removed entirely, not just made optional (V15). The
-- reference file's "phase" column doesn't hold a clean whole number in
-- practice (e.g. "Phase 1" as free text), and the grading-ingestion
-- pipeline no longer resolves a module via sheet name + phase at all — it
-- resolves a row's lab directly by (Lab Title, NSP's specialization),
-- mirroring how Gate4ScoreSheetValidator already validates the same
-- workbook at stand-up. There is nothing left that reads this column.
-- ============================================================

ALTER TABLE modules DROP CONSTRAINT uq_module_sequence;
ALTER TABLE modules DROP COLUMN sequence;
