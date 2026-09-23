-- Operational closed loop for published exam plans. All mutable runtime records live outside the
-- immutable revision graph and keep tenant-qualified foreign keys.

CREATE TABLE exam_plan_leave_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  teacher_id uuid NOT NULL,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  reason varchar(500) NOT NULL CHECK (nullif(btrim(reason), '') IS NOT NULL),
  status varchar(24) NOT NULL DEFAULT 'PENDING'
    CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  idempotency_key varchar(160),
  requested_by uuid NOT NULL REFERENCES app_user(id),
  decided_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  decision_reason varchar(500),
  decided_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  CHECK (status = 'PENDING' OR decided_at IS NOT NULL),
  UNIQUE (org_id, id),
  UNIQUE (org_id, idempotency_key),
  FOREIGN KEY (org_id, teacher_id) REFERENCES teacher(org_id, id)
);
CREATE INDEX idx_v29_leave_request_teacher_time
  ON exam_plan_leave_request(org_id, teacher_id, starts_at, ends_at);
CREATE INDEX idx_v29_leave_request_queue
  ON exam_plan_leave_request(org_id, status, created_at DESC);
ALTER TABLE exam_plan_leave_request
  ADD CONSTRAINT ex_v29_approved_teacher_leave
  EXCLUDE USING gist (
    org_id WITH =,
    teacher_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status = 'APPROVED');

ALTER TABLE exam_plan_substitution_case
  ADD COLUMN leave_request_id uuid,
  ADD COLUMN resolved_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  ADD COLUMN resolved_at timestamptz,
  ADD COLUMN resolution_reason varchar(500);
ALTER TABLE exam_plan_substitution_case
  ADD CONSTRAINT fk_v29_substitution_leave_tenant
  FOREIGN KEY (org_id, leave_request_id)
  REFERENCES exam_plan_leave_request(org_id, id) ON DELETE SET NULL;
CREATE INDEX idx_v29_substitution_revision_queue
  ON exam_plan_substitution_case(org_id, revision_id, status, created_at);

ALTER TABLE exam_plan_delivery
  ADD COLUMN attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN last_attempt_at timestamptz,
  ADD COLUMN failure_reason varchar(500),
  ADD COLUMN provider_message_id varchar(160),
  ADD COLUMN delivered_at timestamptz,
  ADD COLUMN read_at timestamptz;
CREATE INDEX idx_v29_delivery_dispatch
  ON exam_plan_delivery(org_id, status, next_attempt_at, created_at)
  WHERE status IN ('PENDING','FAILED');
CREATE UNIQUE INDEX uq_v29_delivery_provider_message
  ON exam_plan_delivery(org_id, channel, provider_message_id)
  WHERE provider_message_id IS NOT NULL;

CREATE TABLE exam_plan_incident (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  incident_code varchar(64) NOT NULL,
  incident_type varchar(48) NOT NULL,
  severity varchar(16) NOT NULL CHECK (severity IN ('INFO','LOW','MEDIUM','HIGH','CRITICAL')),
  summary varchar(240) NOT NULL CHECK (nullif(btrim(summary), '') IS NOT NULL),
  details jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED','CANCELLED')),
  reported_by uuid NOT NULL REFERENCES app_user(id),
  resolved_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  resolved_at timestamptz,
  resolution varchar(500),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, revision_id, incident_code),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_v29_incident_revision_status
  ON exam_plan_incident(org_id, revision_id, status, severity, created_at DESC);

CREATE TABLE exam_plan_checklist_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  item_code varchar(64) NOT NULL,
  label varchar(240) NOT NULL CHECK (nullif(btrim(label), '') IS NOT NULL),
  required boolean NOT NULL DEFAULT true,
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','WAIVED')),
  evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
  completed_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  completed_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, revision_id, item_code),
  UNIQUE (org_id, id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE,
  CHECK (status = 'PENDING' OR completed_at IS NOT NULL)
);
CREATE INDEX idx_v29_checklist_revision_status
  ON exam_plan_checklist_item(org_id, revision_id, required, status, created_at);
