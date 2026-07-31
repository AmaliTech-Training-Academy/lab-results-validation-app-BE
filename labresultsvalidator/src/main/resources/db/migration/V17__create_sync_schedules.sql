CREATE TABLE sync_schedules (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(150),
    cohort_id    UUID         REFERENCES cohorts(id),
    frequency    VARCHAR(20)  NOT NULL
                     CONSTRAINT chk_sync_schedule_frequency CHECK (frequency IN ('DAILY','WEEKLY')),
    time_of_day  TIME         NOT NULL,
    day_of_week  VARCHAR(10)
                     CONSTRAINT chk_sync_schedule_day_of_week CHECK (day_of_week IN
                         ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),
    timezone     VARCHAR(50)  NOT NULL DEFAULT 'GMT',
    enabled      BOOLEAN      NOT NULL DEFAULT true,
    created_by   UUID,
    updated_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_sync_schedule_weekly_day CHECK (frequency <> 'WEEKLY' OR day_of_week IS NOT NULL)
);

CREATE INDEX idx_sync_schedules_cohort_id ON sync_schedules (cohort_id);
CREATE INDEX idx_sync_schedules_enabled ON sync_schedules (enabled);
