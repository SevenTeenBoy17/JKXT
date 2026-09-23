-- Explicit invigilation qualifications. Existing teachers are grandfathered to preserve already
-- published/legacy schedules; newly created teachers default to ordinary INVIGILATOR eligibility
-- and must be explicitly granted CHIEF before receiving a chief duty.
ALTER TABLE teacher
  ADD COLUMN qualifications jsonb,
  ADD COLUMN qualification_expiry date,
  ADD COLUMN max_daily_sessions integer NOT NULL DEFAULT 4,
  ADD COLUMN preferred_campus varchar(120);

UPDATE teacher
SET qualifications = '["INVIGILATOR","CHIEF"]'::jsonb;

ALTER TABLE teacher
  ALTER COLUMN qualifications SET DEFAULT '["INVIGILATOR"]'::jsonb,
  ALTER COLUMN qualifications SET NOT NULL,
  ADD CONSTRAINT chk_teacher_qualifications_array
    CHECK (jsonb_typeof(qualifications) = 'array'
      AND qualifications <@ '["INVIGILATOR","CHIEF"]'::jsonb),
  ADD CONSTRAINT chk_teacher_max_daily_sessions
    CHECK (max_daily_sessions BETWEEN 1 AND 12),
  ADD CONSTRAINT chk_teacher_preferred_campus
    CHECK (preferred_campus IS NULL OR length(trim(preferred_campus)) > 0);
