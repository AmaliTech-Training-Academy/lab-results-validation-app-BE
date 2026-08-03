-- ============================================================
-- D6: audit records are append-only (PRD Epic D, D6 AC1).
-- ============================================================

-- audit_event and lab_reference_audit_log: nothing in the app ever legitimately
-- updates or deletes these after insert. Block both unconditionally.
CREATE OR REPLACE FUNCTION block_audit_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% records are append-only and cannot be % (id=%)', TG_TABLE_NAME, TG_OP, OLD.id;
END;
$$;

CREATE TRIGGER trg_audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION block_audit_mutation();

CREATE TRIGGER trg_lab_reference_audit_log_append_only
    BEFORE UPDATE OR DELETE ON lab_reference_audit_log
    FOR EACH ROW EXECUTE FUNCTION block_audit_mutation();

-- ingestion_runs: GradingIngestionService legitimately creates a row as status='processing'
-- then updates it once to finalize (status/counts). Allow that one transition, block any
-- update once a row is already in a terminal status, and block delete always.
CREATE OR REPLACE FUNCTION block_finalized_ingestion_run_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ingestion_runs records are append-only and cannot be deleted (id=%)', OLD.id;
    END IF;
    IF OLD.status <> 'processing' THEN
        RAISE EXCEPTION 'ingestion_runs record % is already finalized (status=%) and cannot be modified',
            OLD.id, OLD.status;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ingestion_runs_append_only
    BEFORE UPDATE OR DELETE ON ingestion_runs
    FOR EACH ROW EXECUTE FUNCTION block_finalized_ingestion_run_mutation();
