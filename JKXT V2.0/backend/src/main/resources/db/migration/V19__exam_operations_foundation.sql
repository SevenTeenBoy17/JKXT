CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE app_user
  ADD COLUMN IF NOT EXISTS preferences jsonb NOT NULL DEFAULT
  '{"theme":{"version":1,"mode":"system","paletteSource":"school","palette":"indigo"}}'::jsonb;

UPDATE organization
SET settings = jsonb_set(
    coalesce(settings, '{}'::jsonb),
    '{appearance}',
    '{"version":1,"defaultPalette":"indigo"}'::jsonb,
    true
  ),
  updated_at = now()
WHERE NOT coalesce(settings, '{}'::jsonb) ? 'appearance';

CREATE TABLE exam_plan (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  plan_no varchar(48) NOT NULL,
  name varchar(160) NOT NULL,
  term_id uuid REFERENCES academic_term(id) ON DELETE SET NULL,
  status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','VALIDATING','READY','SOLVING','REVIEW_REQUIRED','PENDING_APPROVAL',
    'APPROVED','PUBLISHED','EXECUTING','CLOSED','ARCHIVED',
    'INVALID','INFEASIBLE','FAILED','CANCELLED'
  )),
  active_revision_id uuid,
  legacy_exam_task_id uuid REFERENCES exam_task(id) ON DELETE SET NULL,
  settings jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, plan_no)
);
CREATE INDEX idx_exam_plan_org_status ON exam_plan(org_id, status, updated_at DESC);
CREATE UNIQUE INDEX uq_exam_plan_legacy_task ON exam_plan(org_id, legacy_exam_task_id)
  WHERE legacy_exam_task_id IS NOT NULL;

CREATE TABLE exam_plan_revision (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  plan_id uuid NOT NULL REFERENCES exam_plan(id) ON DELETE CASCADE,
  revision_no int NOT NULL CHECK (revision_no > 0),
  predecessor_id uuid REFERENCES exam_plan_revision(id) ON DELETE SET NULL,
  status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (status IN (
    'DRAFT','VALIDATING','READY','SOLVING','REVIEW_REQUIRED','PENDING_APPROVAL',
    'APPROVED','PUBLISHED','EXECUTING','CLOSED','ARCHIVED',
    'INVALID','INFEASIBLE','FAILED','CANCELLED'
  )),
  source_type varchar(24) NOT NULL DEFAULT 'MANUAL' CHECK (source_type IN ('MANUAL','IMPORT','LEGACY','API','SUCCESSOR')),
  title varchar(160),
  change_reason varchar(500),
  configuration jsonb NOT NULL DEFAULT '{}'::jsonb,
  validation_summary jsonb NOT NULL DEFAULT '{}'::jsonb,
  submitted_at timestamptz,
  approved_at timestamptz,
  published_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, plan_id, revision_no)
);
CREATE INDEX idx_plan_revision_plan ON exam_plan_revision(org_id, plan_id, revision_no DESC);

ALTER TABLE exam_plan
  ADD CONSTRAINT fk_exam_plan_active_revision
  FOREIGN KEY (active_revision_id) REFERENCES exam_plan_revision(id) ON DELETE SET NULL;

CREATE TABLE exam_plan_session (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  exam_id uuid REFERENCES exam(id) ON DELETE SET NULL,
  session_code varchar(64) NOT NULL,
  name varchar(160) NOT NULL,
  subject_name varchar(80),
  grade varchar(40),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  candidate_count int NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
  status varchar(24) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','LOCKED','CANCELLED')),
  requirements jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  UNIQUE (org_id, revision_id, session_code)
);
CREATE INDEX idx_plan_session_revision_time ON exam_plan_session(org_id, revision_id, starts_at);

CREATE TABLE exam_plan_room_assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  session_id uuid NOT NULL REFERENCES exam_plan_session(id) ON DELETE CASCADE,
  classroom_id uuid NOT NULL REFERENCES classroom(id),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  planned_capacity int NOT NULL CHECK (planned_capacity >= 0),
  allocated_candidates int NOT NULL DEFAULT 0 CHECK (allocated_candidates >= 0),
  status varchar(24) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','LOCKED','CANCELLED')),
  required_features jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  CHECK (allocated_candidates <= planned_capacity),
  UNIQUE (org_id, revision_id, session_id, classroom_id),
  EXCLUDE USING gist (
    org_id WITH =,
    classroom_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX idx_plan_room_revision ON exam_plan_room_assignment(org_id, revision_id, starts_at);

CREATE TABLE exam_plan_seat_assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  session_id uuid NOT NULL REFERENCES exam_plan_session(id) ON DELETE CASCADE,
  room_assignment_id uuid NOT NULL REFERENCES exam_plan_room_assignment(id) ON DELETE CASCADE,
  candidate_external_id varchar(96) NOT NULL,
  seat_label varchar(48) NOT NULL,
  special_arrangement jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED','CANCELLED')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, revision_id, session_id, candidate_external_id),
  UNIQUE (org_id, room_assignment_id, seat_label)
);
CREATE INDEX idx_plan_seat_revision ON exam_plan_seat_assignment(org_id, revision_id, session_id);

CREATE TABLE exam_plan_constraint (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  constraint_type varchar(64) NOT NULL,
  severity varchar(12) NOT NULL CHECK (severity IN ('HARD','SOFT')),
  resource_type varchar(40),
  resource_id varchar(96),
  expression jsonb NOT NULL DEFAULT '{}'::jsonb,
  enabled boolean NOT NULL DEFAULT true,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_constraint_revision ON exam_plan_constraint(org_id, revision_id, severity);

CREATE TABLE exam_plan_duty_requirement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  session_id uuid NOT NULL REFERENCES exam_plan_session(id) ON DELETE CASCADE,
  room_assignment_id uuid REFERENCES exam_plan_room_assignment(id) ON DELETE CASCADE,
  duty_role varchar(32) NOT NULL CHECK (duty_role IN ('CHIEF','INVIGILATOR','ASSISTANT','ROAMING','RESERVE')),
  required_count int NOT NULL CHECK (required_count > 0),
  qualification jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_duty_requirement_revision ON exam_plan_duty_requirement(org_id, revision_id, session_id);

CREATE TABLE exam_plan_duty_assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  requirement_id uuid NOT NULL REFERENCES exam_plan_duty_requirement(id) ON DELETE CASCADE,
  teacher_id uuid NOT NULL REFERENCES teacher(id),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  duty_role varchar(32) NOT NULL CHECK (duty_role IN ('CHIEF','INVIGILATOR','ASSISTANT','ROAMING','RESERVE')),
  status varchar(24) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING_CONFIRMATION','ACCEPTED','DECLINED','CANCELLED','COMPLETED')),
  source varchar(24) NOT NULL DEFAULT 'SOLVER' CHECK (source IN ('SOLVER','MANUAL','SUBSTITUTION')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  CHECK (ends_at > starts_at),
  EXCLUDE USING gist (
    org_id WITH =,
    teacher_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX idx_duty_assignment_revision ON exam_plan_duty_assignment(org_id, revision_id, starts_at);

CREATE TABLE exam_plan_approval_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
  requested_by uuid NOT NULL REFERENCES app_user(id),
  decided_by uuid REFERENCES app_user(id),
  decision_reason varchar(500),
  decided_at timestamptz,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_pending_plan_approval ON exam_plan_approval_request(org_id, revision_id)
  WHERE status = 'PENDING';

CREATE TABLE exam_plan_change_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  request_type varchar(40) NOT NULL,
  reason varchar(500) NOT NULL,
  impact_analysis jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','APPROVED','REJECTED','APPLIED','CANCELLED')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_change_revision ON exam_plan_change_request(org_id, revision_id, status);

CREATE TABLE exam_plan_emergency_change (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  change_request_id uuid REFERENCES exam_plan_change_request(id) ON DELETE SET NULL,
  emergency_type varchar(40) NOT NULL,
  affected_resources jsonb NOT NULL DEFAULT '[]'::jsonb,
  action_taken jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RESOLVED','CANCELLED')),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_emergency_revision ON exam_plan_emergency_change(org_id, revision_id, status);

CREATE TABLE exam_plan_outbox (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  event_type varchar(64) NOT NULL,
  aggregate_type varchar(48) NOT NULL,
  aggregate_id uuid,
  payload jsonb NOT NULL DEFAULT '{}'::jsonb,
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED','CANCELLED')),
  available_at timestamptz NOT NULL DEFAULT now(),
  attempt_count int NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  last_error varchar(500),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_outbox_delivery ON exam_plan_outbox(status, available_at);

CREATE TABLE exam_plan_delivery (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  outbox_id uuid NOT NULL REFERENCES exam_plan_outbox(id) ON DELETE CASCADE,
  recipient_type varchar(32) NOT NULL,
  recipient_id varchar(96) NOT NULL,
  channel varchar(24) NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENT','DELIVERED','READ','FAILED','CANCELLED')),
  receipt jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, outbox_id, recipient_type, recipient_id, channel)
);
CREATE INDEX idx_plan_delivery_status ON exam_plan_delivery(org_id, status, updated_at);

CREATE TABLE exam_plan_audit_event (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  plan_id uuid REFERENCES exam_plan(id) ON DELETE CASCADE,
  revision_id uuid REFERENCES exam_plan_revision(id) ON DELETE CASCADE,
  action varchar(64) NOT NULL,
  actor_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  reason varchar(500),
  before_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  after_state jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0
);
CREATE INDEX idx_plan_audit_revision ON exam_plan_audit_event(org_id, revision_id, created_at DESC);

CREATE TABLE api_idempotency_record (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  idempotency_key varchar(160) NOT NULL,
  operation varchar(80) NOT NULL,
  resource_type varchar(48) NOT NULL,
  resource_id uuid NOT NULL,
  request_hash varchar(80),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, operation, idempotency_key)
);

-- Composite ownership keys make org_id part of every parent/child reference. A UUID that exists in
-- another tenant therefore cannot be paired with the caller's org_id, even if a future importer
-- accidentally bypasses the service-layer tenant predicate.
ALTER TABLE academic_term ADD CONSTRAINT uq_v19_term_org_id UNIQUE (org_id, id);
ALTER TABLE exam_task ADD CONSTRAINT uq_v19_task_org_id UNIQUE (org_id, id);
ALTER TABLE exam ADD CONSTRAINT uq_v19_exam_org_id UNIQUE (org_id, id);
ALTER TABLE classroom ADD CONSTRAINT uq_v19_classroom_org_id UNIQUE (org_id, id);
ALTER TABLE teacher ADD CONSTRAINT uq_v19_teacher_org_id UNIQUE (org_id, id);

ALTER TABLE exam_plan ADD CONSTRAINT uq_v19_plan_org_id UNIQUE (org_id, id);
ALTER TABLE exam_plan_revision ADD CONSTRAINT uq_v19_revision_org_id UNIQUE (org_id, id);
ALTER TABLE exam_plan_revision ADD CONSTRAINT uq_v19_revision_plan_id UNIQUE (org_id, plan_id, id);
ALTER TABLE exam_plan_session ADD CONSTRAINT uq_v19_session_revision_id UNIQUE (org_id, revision_id, id);
ALTER TABLE exam_plan_room_assignment ADD CONSTRAINT uq_v19_room_session_id
  UNIQUE (org_id, revision_id, session_id, id);
ALTER TABLE exam_plan_duty_requirement ADD CONSTRAINT uq_v19_requirement_revision_id
  UNIQUE (org_id, revision_id, id);
ALTER TABLE exam_plan_change_request ADD CONSTRAINT uq_v19_change_revision_id
  UNIQUE (org_id, revision_id, id);
ALTER TABLE exam_plan_outbox ADD CONSTRAINT uq_v19_outbox_org_id UNIQUE (org_id, id);

ALTER TABLE exam_plan ADD CONSTRAINT fk_v19_plan_term_tenant
  FOREIGN KEY (org_id, term_id) REFERENCES academic_term(org_id, id);
ALTER TABLE exam_plan ADD CONSTRAINT fk_v19_plan_legacy_tenant
  FOREIGN KEY (org_id, legacy_exam_task_id) REFERENCES exam_task(org_id, id);
ALTER TABLE exam_plan ADD CONSTRAINT fk_v19_plan_active_revision_tenant
  FOREIGN KEY (org_id, active_revision_id) REFERENCES exam_plan_revision(org_id, id);

ALTER TABLE exam_plan_revision ADD CONSTRAINT fk_v19_revision_plan_tenant
  FOREIGN KEY (org_id, plan_id) REFERENCES exam_plan(org_id, id);
ALTER TABLE exam_plan_revision ADD CONSTRAINT fk_v19_revision_predecessor_plan
  FOREIGN KEY (org_id, plan_id, predecessor_id) REFERENCES exam_plan_revision(org_id, plan_id, id);

ALTER TABLE exam_plan_session ADD CONSTRAINT fk_v19_session_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_session ADD CONSTRAINT fk_v19_session_exam_tenant
  FOREIGN KEY (org_id, exam_id) REFERENCES exam(org_id, id);

ALTER TABLE exam_plan_room_assignment ADD CONSTRAINT fk_v19_room_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_room_assignment ADD CONSTRAINT fk_v19_room_session_revision
  FOREIGN KEY (org_id, revision_id, session_id) REFERENCES exam_plan_session(org_id, revision_id, id);
ALTER TABLE exam_plan_room_assignment ADD CONSTRAINT fk_v19_room_classroom_tenant
  FOREIGN KEY (org_id, classroom_id) REFERENCES classroom(org_id, id);

ALTER TABLE exam_plan_seat_assignment ADD CONSTRAINT fk_v19_seat_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_seat_assignment ADD CONSTRAINT fk_v19_seat_session_revision
  FOREIGN KEY (org_id, revision_id, session_id) REFERENCES exam_plan_session(org_id, revision_id, id);
ALTER TABLE exam_plan_seat_assignment ADD CONSTRAINT fk_v19_seat_room_session_revision
  FOREIGN KEY (org_id, revision_id, session_id, room_assignment_id)
  REFERENCES exam_plan_room_assignment(org_id, revision_id, session_id, id);

ALTER TABLE exam_plan_constraint ADD CONSTRAINT fk_v19_constraint_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);

ALTER TABLE exam_plan_duty_requirement ADD CONSTRAINT fk_v19_requirement_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_duty_requirement ADD CONSTRAINT fk_v19_requirement_session_revision
  FOREIGN KEY (org_id, revision_id, session_id) REFERENCES exam_plan_session(org_id, revision_id, id);
ALTER TABLE exam_plan_duty_requirement ADD CONSTRAINT fk_v19_requirement_room_session_revision
  FOREIGN KEY (org_id, revision_id, session_id, room_assignment_id)
  REFERENCES exam_plan_room_assignment(org_id, revision_id, session_id, id);

ALTER TABLE exam_plan_duty_assignment ADD CONSTRAINT fk_v19_duty_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_duty_assignment ADD CONSTRAINT fk_v19_duty_requirement_revision
  FOREIGN KEY (org_id, revision_id, requirement_id)
  REFERENCES exam_plan_duty_requirement(org_id, revision_id, id);
ALTER TABLE exam_plan_duty_assignment ADD CONSTRAINT fk_v19_duty_teacher_tenant
  FOREIGN KEY (org_id, teacher_id) REFERENCES teacher(org_id, id);

ALTER TABLE exam_plan_approval_request ADD CONSTRAINT fk_v19_approval_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_change_request ADD CONSTRAINT fk_v19_change_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_emergency_change ADD CONSTRAINT fk_v19_emergency_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_emergency_change ADD CONSTRAINT fk_v19_emergency_change_revision
  FOREIGN KEY (org_id, revision_id, change_request_id)
  REFERENCES exam_plan_change_request(org_id, revision_id, id);
ALTER TABLE exam_plan_outbox ADD CONSTRAINT fk_v19_outbox_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);
ALTER TABLE exam_plan_delivery ADD CONSTRAINT fk_v19_delivery_outbox_tenant
  FOREIGN KEY (org_id, outbox_id) REFERENCES exam_plan_outbox(org_id, id);
ALTER TABLE exam_plan_audit_event ADD CONSTRAINT fk_v19_audit_plan_tenant
  FOREIGN KEY (org_id, plan_id) REFERENCES exam_plan(org_id, id);
ALTER TABLE exam_plan_audit_event ADD CONSTRAINT fk_v19_audit_revision_tenant
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id);

CREATE OR REPLACE FUNCTION prevent_published_revision_content_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED') AND (
    NEW.plan_id IS DISTINCT FROM OLD.plan_id OR
    NEW.revision_no IS DISTINCT FROM OLD.revision_no OR
    NEW.predecessor_id IS DISTINCT FROM OLD.predecessor_id OR
    NEW.source_type IS DISTINCT FROM OLD.source_type OR
    NEW.title IS DISTINCT FROM OLD.title OR
    NEW.change_reason IS DISTINCT FROM OLD.change_reason OR
    NEW.configuration IS DISTINCT FROM OLD.configuration OR
    NEW.validation_summary IS DISTINCT FROM OLD.validation_summary
  ) THEN
    RAISE EXCEPTION 'Published plan revision content is immutable' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_plan_revision_immutable
BEFORE UPDATE ON exam_plan_revision
FOR EACH ROW EXECUTE FUNCTION prevent_published_revision_content_change();

CREATE OR REPLACE FUNCTION prevent_immutable_revision_child_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  target_revision uuid;
  target_status varchar(32);
BEGIN
  IF TG_OP = 'DELETE' THEN
    target_revision := OLD.revision_id;
  ELSE
    target_revision := NEW.revision_id;
  END IF;
  SELECT status INTO target_status FROM exam_plan_revision WHERE id = target_revision;
  IF target_status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED') THEN
    RAISE EXCEPTION 'Published plan revision resources are immutable' USING ERRCODE = '23514';
  END IF;
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_plan_session_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_session
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_plan_room_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_room_assignment
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_plan_seat_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_seat_assignment
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_plan_constraint_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_constraint
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_plan_duty_requirement_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_duty_requirement
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_plan_duty_assignment_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_duty_assignment
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
