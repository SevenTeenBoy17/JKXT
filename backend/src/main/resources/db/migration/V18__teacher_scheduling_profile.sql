-- Real-school scheduling profile. Ordered JSON arrays preserve the operator's input order while
-- keeping leave/duty windows extensible (for example, an optional human-readable reason).
ALTER TABLE teacher
  ADD COLUMN age integer,
  ADD COLUMN position varchar(80),
  ADD COLUMN leave_windows jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN functional_accommodations jsonb NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN duty_windows jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE teacher
  ADD CONSTRAINT chk_teacher_age_positive
    CHECK (age IS NULL OR age > 0),
  ADD CONSTRAINT chk_teacher_leave_windows_array
    CHECK (jsonb_typeof(leave_windows) = 'array'),
  ADD CONSTRAINT chk_teacher_functional_accommodations_array
    CHECK (jsonb_typeof(functional_accommodations) = 'array'),
  ADD CONSTRAINT chk_teacher_duty_windows_array
    CHECK (jsonb_typeof(duty_windows) = 'array');
