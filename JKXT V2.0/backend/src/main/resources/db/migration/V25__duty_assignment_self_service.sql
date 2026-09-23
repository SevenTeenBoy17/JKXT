-- Teacher duty acknowledgement is operational state, not revision content. Keep it outside the
-- immutable published revision graph while retaining tenant-qualified foreign keys and history.

ALTER TABLE exam_plan_duty_assignment
  ADD CONSTRAINT uq_v25_duty_assignment_org_id UNIQUE (org_id, id);

CREATE TABLE exam_plan_duty_response (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  assignment_id uuid NOT NULL,
  teacher_id uuid NOT NULL,
  status varchar(32) NOT NULL CHECK (status IN (
    'PENDING_CONFIRMATION','ACCEPTED','REJECTED','CHECKED_IN'
  )),
  reason varchar(500),
  responded_at timestamptz,
  checked_in_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, assignment_id),
  FOREIGN KEY (org_id, assignment_id)
    REFERENCES exam_plan_duty_assignment(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, teacher_id) REFERENCES teacher(org_id, id),
  CHECK (status <> 'REJECTED' OR nullif(btrim(reason), '') IS NOT NULL),
  CHECK (status <> 'CHECKED_IN' OR (responded_at IS NOT NULL AND checked_in_at IS NOT NULL))
);
CREATE INDEX idx_v25_duty_response_teacher
  ON exam_plan_duty_response(org_id, teacher_id, updated_at DESC);

CREATE TABLE exam_plan_substitution_case (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  duty_assignment_id uuid NOT NULL,
  teacher_id uuid NOT NULL,
  replacement_teacher_id uuid,
  status varchar(24) NOT NULL DEFAULT 'OPEN'
    CHECK (status IN ('OPEN','ASSIGNED','RESOLVED','CANCELLED')),
  reason varchar(500) NOT NULL CHECK (nullif(btrim(reason), '') IS NOT NULL),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, duty_assignment_id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id),
  FOREIGN KEY (org_id, duty_assignment_id)
    REFERENCES exam_plan_duty_assignment(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, teacher_id) REFERENCES teacher(org_id, id),
  FOREIGN KEY (org_id, replacement_teacher_id) REFERENCES teacher(org_id, id)
);
CREATE INDEX idx_v25_substitution_case_queue
  ON exam_plan_substitution_case(org_id, status, created_at);
