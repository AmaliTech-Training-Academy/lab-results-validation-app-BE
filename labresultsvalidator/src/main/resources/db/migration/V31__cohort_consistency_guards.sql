-- ============================================================
-- Closes the remaining open findings from docs/db-schema-review.md that were
-- left for a follow-up (1.1, 1.2, 1.3, 1.4). Each of the redundant/denormalized
-- columns below is kept exactly as-is (removing them would mean rewriting the
-- queries/entities/indexes that already depend on them, per the review) — what
-- was missing was a DB-level guarantee that the redundant copy can't drift from
-- its source of truth. Verified against every write site in the current
-- codebase before writing this: all of them already keep the pair consistent,
-- so this only forecloses future mistakes, it doesn't change any live behavior.
-- ============================================================

-- ------------------------------------------------------------
-- 1.1 learners.cohort_id must match specializations.cohort_id for the row's
-- specialization_id. (ReferenceCommitService.persistLearners resolves both
-- from the same in-flight cohort commit today, so this always holds; nothing
-- enforced it at the DB level.)
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_learner_cohort_matches_specialization()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    spec_cohort_id UUID;
BEGIN
    SELECT cohort_id INTO spec_cohort_id FROM specializations WHERE id = NEW.specialization_id;
    IF spec_cohort_id IS NOT NULL AND NEW.cohort_id IS DISTINCT FROM spec_cohort_id THEN
        RAISE EXCEPTION
            'learners.cohort_id (%) does not match specializations.cohort_id (%) for specialization_id %',
            NEW.cohort_id, spec_cohort_id, NEW.specialization_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_learners_cohort_consistency
    BEFORE INSERT OR UPDATE ON learners
    FOR EACH ROW EXECUTE FUNCTION check_learner_cohort_matches_specialization();

-- ------------------------------------------------------------
-- 1.2 ingestion_conflicts.cohort_id must match ingestion_runs.cohort_id for
-- the row's ingestion_run_id (NOT NULL, so this is a pure derived copy).
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_conflict_cohort_matches_run()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    run_cohort_id UUID;
BEGIN
    SELECT cohort_id INTO run_cohort_id FROM ingestion_runs WHERE id = NEW.ingestion_run_id;
    IF run_cohort_id IS NOT NULL AND NEW.cohort_id IS DISTINCT FROM run_cohort_id THEN
        RAISE EXCEPTION
            'ingestion_conflicts.cohort_id (%) does not match ingestion_runs.cohort_id (%) for ingestion_run_id %',
            NEW.cohort_id, run_cohort_id, NEW.ingestion_run_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_conflicts_cohort_consistency
    BEFORE INSERT OR UPDATE ON ingestion_conflicts
    FOR EACH ROW EXECUTE FUNCTION check_conflict_cohort_matches_run();

-- ------------------------------------------------------------
-- 1.3 notifications.cohort_id must match ingestion_runs.cohort_id (when
-- ingestion_run_id is set) and cohort_sync_jobs.cohort_id (when sync_job_id
-- is set). Both are nullable — a notification with neither is untouched by
-- this check, since cohort_id is then its only source of truth.
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_notification_cohort_consistency()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    run_cohort_id UUID;
    job_cohort_id UUID;
BEGIN
    IF NEW.ingestion_run_id IS NOT NULL THEN
        SELECT cohort_id INTO run_cohort_id FROM ingestion_runs WHERE id = NEW.ingestion_run_id;
        IF run_cohort_id IS NOT NULL AND NEW.cohort_id IS DISTINCT FROM run_cohort_id THEN
            RAISE EXCEPTION
                'notifications.cohort_id (%) does not match ingestion_runs.cohort_id (%) for ingestion_run_id %',
                NEW.cohort_id, run_cohort_id, NEW.ingestion_run_id;
        END IF;
    END IF;

    IF NEW.sync_job_id IS NOT NULL THEN
        SELECT cohort_id INTO job_cohort_id FROM cohort_sync_jobs WHERE id = NEW.sync_job_id;
        IF job_cohort_id IS NOT NULL AND NEW.cohort_id IS DISTINCT FROM job_cohort_id THEN
            RAISE EXCEPTION
                'notifications.cohort_id (%) does not match cohort_sync_jobs.cohort_id (%) for sync_job_id %',
                NEW.cohort_id, job_cohort_id, NEW.sync_job_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notifications_cohort_consistency
    BEFORE INSERT OR UPDATE ON notifications
    FOR EACH ROW EXECUTE FUNCTION check_notification_cohort_consistency();

-- ------------------------------------------------------------
-- 1.4 notifications recipient columns are a polymorphic pair selected by
-- recipient_kind. Verified every write site in NotificationStagingService/
-- NotificationAlertService already sets exactly one of the two IDs to match
-- recipient_kind — this makes that the only legal shape going forward.
-- ------------------------------------------------------------
ALTER TABLE notifications
    ADD CONSTRAINT chk_notif_recipient CHECK (
        (recipient_kind = 'instructor' AND recipient_instructor_id IS NOT NULL AND recipient_user_id IS NULL) OR
        (recipient_kind = 'admin'      AND recipient_user_id       IS NOT NULL AND recipient_instructor_id IS NULL)
    );
