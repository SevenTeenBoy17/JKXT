-- Executable exam-operations wave: idempotent collection ingestion, persisted planning jobs,
-- diagnostic cases and immutable-successor emergency changes. This migration is additive.

CREATE TABLE exam_plan_bulk_ingest (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  resource_kind varchar(32) NOT NULL CHECK (resource_kind IN ('sessions','rooms','seats','constraints')),
  idempotency_key varchar(160) NOT NULL,
  accepted_count int NOT NULL CHECK (accepted_count >= 0),
  result jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  UNIQUE (org_id, revision_id, resource_kind, idempotency_key),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE
);

CREATE TABLE exam_plan_planning_job (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  plan_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  status varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK (status IN (
    'QUEUED','RUNNING','SUCCEEDED','INFEASIBLE','INVALID','FAILED','CANCELLED'
  )),
  stage varchar(40) NOT NULL DEFAULT 'QUEUED',
  progress int NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  request jsonb NOT NULL DEFAULT '{}'::jsonb,
  diagnostics jsonb NOT NULL DEFAULT '[]'::jsonb,
  stats jsonb NOT NULL DEFAULT '{}'::jsonb,
  started_at timestamptz,
  finished_at timestamptz,
  cancel_requested boolean NOT NULL DEFAULT false,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (org_id, plan_id) REFERENCES exam_plan(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_exam_plan_planning_job_revision
  ON exam_plan_planning_job(org_id, revision_id, created_at DESC);

CREATE TABLE exam_plan_diagnostic_case (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  plan_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  request jsonb NOT NULL,
  status varchar(24) NOT NULL CHECK (status IN ('INVALID','INFEASIBLE','FEASIBLE')),
  issues jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  updated_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  FOREIGN KEY (org_id, plan_id) REFERENCES exam_plan(org_id, id) ON DELETE CASCADE,
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE
);

ALTER TABLE exam_plan_emergency_change
  ADD COLUMN successor_revision_id uuid,
  ADD COLUMN idempotency_key varchar(160),
  ADD COLUMN retention_rate numeric(8,6),
  ADD COLUMN impact_analysis jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE exam_plan_emergency_change
  ADD CONSTRAINT fk_v20_emergency_successor_tenant
  FOREIGN KEY (org_id, successor_revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE SET NULL;
CREATE UNIQUE INDEX uq_v20_emergency_idempotency
  ON exam_plan_emergency_change(org_id, revision_id, idempotency_key)
  WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_v20_duty_requirement_slot_role
  ON exam_plan_duty_requirement(org_id, revision_id, session_id, room_assignment_id, duty_role);

-- Dedupe constraints with the same natural external-resource key inside a revision. Expression is
-- intentionally excluded: a changed expression must be a new version/revision, not an overwrite.
CREATE UNIQUE INDEX uq_v20_plan_constraint_natural
  ON exam_plan_constraint(org_id, revision_id, constraint_type, severity,
                          coalesce(resource_type, ''), coalesce(resource_id, ''));

-- Versioned resources in different revisions are alternatives, not simultaneous allocations.
-- The V19 exclusions intentionally guarded overlaps but omitted revision_id, which prevented an
-- immutable successor from carrying forward unchanged rooms/teachers. Keep overlap protection
-- inside each version while allowing published predecessor + draft successor to coexist.
ALTER TABLE exam_plan_room_assignment
  DROP CONSTRAINT IF EXISTS exam_plan_room_assignment_org_id_classroom_id_tstzrange_excl;
ALTER TABLE exam_plan_room_assignment
  ADD CONSTRAINT ex_v20_room_time_per_revision EXCLUDE USING gist (
    org_id WITH =,
    revision_id WITH =,
    classroom_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED');

ALTER TABLE exam_plan_duty_assignment
  DROP CONSTRAINT IF EXISTS exam_plan_duty_assignment_org_id_teacher_id_tstzrange_excl;
ALTER TABLE exam_plan_duty_assignment
  ADD CONSTRAINT ex_v20_teacher_time_per_revision EXCLUDE USING gist (
    org_id WITH =,
    revision_id WITH =,
    teacher_id WITH =,
    tstzrange(starts_at, ends_at, '[)') WITH &&
  ) WHERE (status <> 'CANCELLED');
