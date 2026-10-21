UPDATE schedule_run
SET status = 'pending'
WHERE status = 'queued';

ALTER TABLE schedule_run
  ALTER COLUMN status SET DEFAULT 'pending';

ALTER TABLE schedule_run
  DROP CONSTRAINT IF EXISTS schedule_run_status_check;

ALTER TABLE schedule_run
  ADD CONSTRAINT schedule_run_status_check
  CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'infeasible', 'fallback', 'cancelled'));
