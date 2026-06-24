UPDATE cohorts SET is_locked = true WHERE is_locked = false;
ALTER TABLE cohorts ALTER COLUMN is_locked SET DEFAULT true;
