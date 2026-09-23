-- Exam-operations remediation: teacher self-service isolation, revision-level planning
-- concurrency, and complete OLD/NEW immutable revision membership checks.

-- TEACHER_QUERY is a self-service role, not a same-tenant global read role.
UPDATE role
SET permissions = (
      SELECT coalesce(jsonb_agg(DISTINCT permission), '[]'::jsonb)
      FROM jsonb_array_elements_text((permissions - 'tenant:read') || '["duty:self"]'::jsonb) AS p(permission)
    ),
    updated_at = now()
WHERE code = 'TEACHER_QUERY';

-- A durable tenant-scoped identity link prevents a caller from selecting another teacher id.
ALTER TABLE app_user ADD CONSTRAINT uq_v22_app_user_org_id UNIQUE (org_id, id);
ALTER TABLE teacher ADD COLUMN app_user_id uuid;
ALTER TABLE teacher ADD CONSTRAINT fk_v22_teacher_user_tenant
  FOREIGN KEY (org_id, app_user_id) REFERENCES app_user(org_id, id);
CREATE UNIQUE INDEX uq_v22_teacher_app_user
  ON teacher(org_id, app_user_id) WHERE app_user_id IS NOT NULL;

-- Reconcile any legacy race deterministically, then make the invariant database-enforced.
WITH ranked AS (
  SELECT id, row_number() OVER (
    PARTITION BY org_id, revision_id ORDER BY created_at DESC, id DESC
  ) position
  FROM exam_plan_planning_job
  WHERE status IN ('QUEUED','RUNNING')
)
UPDATE exam_plan_planning_job job
SET status='FAILED', stage='CONCURRENCY_RECONCILED', progress=100,
    diagnostics='[{"code":"PLANNING_CONCURRENCY_RECONCILED","severity":"HARD","message":"A newer active job superseded this legacy concurrent job"}]'::jsonb,
    finished_at=now(), updated_at=now(), version=version+1
FROM ranked
WHERE job.id=ranked.id AND ranked.position>1;

CREATE UNIQUE INDEX uq_v22_one_active_planning_job
  ON exam_plan_planning_job(org_id, revision_id)
  WHERE status IN ('QUEUED','RUNNING');

CREATE OR REPLACE FUNCTION prevent_immutable_revision_child_change()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  old_status varchar(32);
  new_status varchar(32);
BEGIN
  IF TG_OP IN ('UPDATE','DELETE') THEN
    SELECT status INTO old_status
    FROM exam_plan_revision
    WHERE org_id=OLD.org_id AND id=OLD.revision_id;
    IF old_status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Published source plan revision resources are immutable' USING ERRCODE = '23514';
    END IF;
  END IF;

  IF TG_OP IN ('INSERT','UPDATE') THEN
    SELECT status INTO new_status
    FROM exam_plan_revision
    WHERE org_id=NEW.org_id AND id=NEW.revision_id;
    IF new_status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Published target plan revision resources are immutable' USING ERRCODE = '23514';
    END IF;
  END IF;

  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  RETURN NEW;
END;
$$;

-- Prevent a single UPDATE from publishing a revision while changing its immutable content.
CREATE OR REPLACE FUNCTION prevent_published_revision_content_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF (OLD.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
      OR NEW.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')) AND (
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
