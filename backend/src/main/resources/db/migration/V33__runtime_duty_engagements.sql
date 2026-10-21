-- Published assignments remain immutable. Each operational hand-over owns a separate response.
-- Engagement zero is the original teacher and is intentionally implicit for old assignments.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM exam_plan_duty_response x
    JOIN exam_plan_duty_assignment a ON a.org_id=x.org_id AND a.id=x.assignment_id
    WHERE x.teacher_id<>a.teacher_id
  ) OR EXISTS (
    SELECT 1 FROM exam_plan_substitution_case s
    JOIN exam_plan_duty_assignment a ON a.org_id=s.org_id AND a.id=s.duty_assignment_id
    WHERE s.teacher_id<>a.teacher_id OR s.revision_id<>a.revision_id
       OR (s.status='RESOLVED' AND (s.replacement_teacher_id IS NULL
           OR s.replacement_teacher_id=s.teacher_id OR s.resolved_at IS NULL))
  ) THEN
    RAISE EXCEPTION 'V33 cannot infer legacy duty ownership; inspect mismatched responses/substitution cases before upgrading'
      USING ERRCODE='23514';
  END IF;
END $$;

ALTER TABLE exam_plan_duty_response ADD COLUMN engagement_no bigint NOT NULL DEFAULT 0 CHECK (engagement_no>=0);
ALTER TABLE exam_plan_duty_response DROP CONSTRAINT exam_plan_duty_response_org_id_assignment_id_key;
ALTER TABLE exam_plan_duty_response ADD CONSTRAINT uq_v33_response_engagement UNIQUE(org_id,assignment_id,engagement_no);
ALTER TABLE exam_plan_substitution_case ADD COLUMN engagement_no bigint NOT NULL DEFAULT 0 CHECK (engagement_no>=0);
ALTER TABLE exam_plan_substitution_case DROP CONSTRAINT exam_plan_substitution_case_org_id_duty_assignment_id_key;
ALTER TABLE exam_plan_substitution_case ADD CONSTRAINT uq_v33_case_engagement UNIQUE(org_id,duty_assignment_id,engagement_no);
ALTER TABLE exam_plan_substitution_case ADD CONSTRAINT uq_v33_case_org_id UNIQUE(org_id,id);

CREATE TABLE exam_plan_duty_engagement (
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  assignment_id uuid NOT NULL,
  engagement_no bigint NOT NULL CHECK (engagement_no>0),
  teacher_id uuid NOT NULL,
  substitution_case_id uuid NOT NULL,
  reason varchar(500) NOT NULL CHECK (nullif(btrim(reason),'') IS NOT NULL),
  created_by uuid REFERENCES app_user(id) ON DELETE SET NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(org_id,assignment_id,engagement_no),
  UNIQUE(org_id,substitution_case_id),
  FOREIGN KEY(org_id,assignment_id) REFERENCES exam_plan_duty_assignment(org_id,id) ON DELETE CASCADE,
  FOREIGN KEY(org_id,teacher_id) REFERENCES teacher(org_id,id),
  FOREIGN KEY(org_id,substitution_case_id) REFERENCES exam_plan_substitution_case(org_id,id)
);
CREATE INDEX idx_v33_engagement_teacher ON exam_plan_duty_engagement(org_id,teacher_id,assignment_id);

-- Existing resolution is evidence of assignment, not evidence of acceptance. Preserve response 0.
INSERT INTO exam_plan_duty_engagement(org_id,assignment_id,engagement_no,teacher_id,substitution_case_id,reason,created_by,created_at)
SELECT org_id,duty_assignment_id,1,replacement_teacher_id,id,
       coalesce(nullif(btrim(resolution_reason),''),reason),coalesce(resolved_by,updated_by,created_by),
       resolved_at
FROM exam_plan_substitution_case WHERE status='RESOLVED';

CREATE FUNCTION v33_duty_engagement_teacher(tenant uuid,assignment uuid,number bigint)
RETURNS uuid LANGUAGE sql STABLE AS $$
  SELECT CASE WHEN number=0 THEN a.teacher_id ELSE e.teacher_id END
  FROM exam_plan_duty_assignment a
  LEFT JOIN exam_plan_duty_engagement e ON e.org_id=a.org_id AND e.assignment_id=a.id AND e.engagement_no=number
  WHERE a.org_id=tenant AND a.id=assignment
$$;

CREATE FUNCTION guard_v33_duty_engagement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_no bigint; original uuid; source_case exam_plan_substitution_case%ROWTYPE;
BEGIN
  IF TG_OP='DELETE' THEN
    -- Only the existing parent/organization cascade may remove history, not a direct history edit.
    IF EXISTS(SELECT 1 FROM organization WHERE id=OLD.org_id)
       AND EXISTS(SELECT 1 FROM exam_plan_duty_assignment WHERE org_id=OLD.org_id AND id=OLD.assignment_id) THEN
      RAISE EXCEPTION 'Duty engagement history is append-only' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' THEN
    -- Preserve the established ON DELETE SET NULL actor-reference lifecycle only.
    IF OLD.created_by IS NOT NULL AND NEW.created_by IS NULL
       AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=OLD.created_by)
       AND (to_jsonb(NEW)-'created_by')=(to_jsonb(OLD)-'created_by') THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'Duty engagement history is append-only' USING ERRCODE='23514';
  END IF;
  PERFORM id FROM organization WHERE id=NEW.org_id FOR NO KEY UPDATE;
  SELECT teacher_id INTO original FROM exam_plan_duty_assignment
    WHERE org_id=NEW.org_id AND id=NEW.assignment_id FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Duty assignment does not belong to tenant' USING ERRCODE='23514'; END IF;
  SELECT coalesce(max(engagement_no),0) INTO current_no FROM exam_plan_duty_engagement
    WHERE org_id=NEW.org_id AND assignment_id=NEW.assignment_id;
  SELECT * INTO source_case FROM exam_plan_substitution_case
    WHERE org_id=NEW.org_id AND id=NEW.substitution_case_id FOR UPDATE;
  IF NOT FOUND OR NEW.engagement_no<>current_no+1 OR source_case.engagement_no<>current_no
     OR source_case.duty_assignment_id<>NEW.assignment_id OR source_case.status<>'RESOLVED'
     OR source_case.teacher_id IS DISTINCT FROM v33_duty_engagement_teacher(NEW.org_id,NEW.assignment_id,current_no)
     OR source_case.replacement_teacher_id IS DISTINCT FROM NEW.teacher_id
     OR source_case.teacher_id=NEW.teacher_id THEN
    RAISE EXCEPTION 'Duty engagement must extend its current resolved tenant-bound case' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_v33_engagement_guard BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_duty_engagement
FOR EACH ROW EXECUTE FUNCTION guard_v33_duty_engagement();

CREATE FUNCTION guard_v33_duty_response() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE assigned_teacher uuid; current_no bigint;
BEGIN
  IF TG_OP='DELETE' THEN
    IF EXISTS(SELECT 1 FROM organization WHERE id=OLD.org_id)
       AND EXISTS(SELECT 1 FROM exam_plan_duty_assignment WHERE org_id=OLD.org_id AND id=OLD.assignment_id) THEN
      RAISE EXCEPTION 'Duty responses are retained for audit' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
  END IF;
  -- The FK actor lifecycle is the sole allowed mutation of a completed engagement's evidence.
  IF TG_OP='UPDATE'
     AND (to_jsonb(NEW)-ARRAY['created_by','updated_by'])=(to_jsonb(OLD)-ARRAY['created_by','updated_by'])
     AND (NEW.created_by IS NOT DISTINCT FROM OLD.created_by OR
          (NEW.created_by IS NULL AND OLD.created_by IS NOT NULL
           AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=OLD.created_by)))
     AND (NEW.updated_by IS NOT DISTINCT FROM OLD.updated_by OR
          (NEW.updated_by IS NULL AND OLD.updated_by IS NOT NULL
           AND NOT EXISTS(SELECT 1 FROM app_user WHERE id=OLD.updated_by))) THEN RETURN NEW; END IF;
  IF TG_OP='UPDATE' AND (NEW.org_id,NEW.assignment_id,NEW.engagement_no,NEW.teacher_id)
     IS DISTINCT FROM (OLD.org_id,OLD.assignment_id,OLD.engagement_no,OLD.teacher_id) THEN
    RAISE EXCEPTION 'Duty response ownership cannot be rewritten' USING ERRCODE='23514';
  END IF;
  assigned_teacher:=v33_duty_engagement_teacher(NEW.org_id,NEW.assignment_id,NEW.engagement_no);
  IF assigned_teacher IS NULL OR NEW.teacher_id<>assigned_teacher THEN
    RAISE EXCEPTION 'Duty response teacher does not own this engagement' USING ERRCODE='23514';
  END IF;
  SELECT coalesce(max(engagement_no),0) INTO current_no FROM exam_plan_duty_engagement
    WHERE org_id=NEW.org_id AND assignment_id=NEW.assignment_id;
  IF NEW.engagement_no<>current_no THEN
    RAISE EXCEPTION 'Historical duty responses cannot be changed' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_v33_response_owner BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_duty_response
FOR EACH ROW EXECUTE FUNCTION guard_v33_duty_response();

CREATE FUNCTION guard_v33_substitution_case() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE assigned_teacher uuid; assigned_revision uuid;
BEGIN
  IF TG_OP='DELETE' THEN
    IF EXISTS(SELECT 1 FROM organization WHERE id=OLD.org_id)
       AND EXISTS(SELECT 1 FROM exam_plan_duty_assignment WHERE org_id=OLD.org_id AND id=OLD.duty_assignment_id) THEN
      RAISE EXCEPTION 'Substitution cases are retained for audit' USING ERRCODE='23514';
    END IF;
    RETURN OLD;
  END IF;
  IF TG_OP='UPDATE' AND (NEW.org_id,NEW.duty_assignment_id,NEW.engagement_no,NEW.teacher_id,NEW.revision_id)
     IS DISTINCT FROM (OLD.org_id,OLD.duty_assignment_id,OLD.engagement_no,OLD.teacher_id,OLD.revision_id) THEN
    RAISE EXCEPTION 'Substitution case ownership cannot be rewritten' USING ERRCODE='23514';
  END IF;
  IF TG_OP='UPDATE' AND OLD.status='RESOLVED'
     AND (NEW.status,NEW.replacement_teacher_id,NEW.resolution_reason,NEW.resolved_at)
       IS DISTINCT FROM (OLD.status,OLD.replacement_teacher_id,OLD.resolution_reason,OLD.resolved_at) THEN
    RAISE EXCEPTION 'Resolved substitution history cannot be rewritten' USING ERRCODE='23514';
  END IF;
  assigned_teacher:=v33_duty_engagement_teacher(NEW.org_id,NEW.duty_assignment_id,NEW.engagement_no);
  SELECT revision_id INTO assigned_revision FROM exam_plan_duty_assignment
    WHERE org_id=NEW.org_id AND id=NEW.duty_assignment_id;
  IF assigned_teacher IS NULL OR NEW.teacher_id<>assigned_teacher OR NEW.revision_id IS DISTINCT FROM assigned_revision THEN
    RAISE EXCEPTION 'Substitution case teacher/revision does not own this engagement' USING ERRCODE='23514';
  END IF;
  IF NEW.status='RESOLVED' AND (NEW.replacement_teacher_id IS NULL
      OR NEW.replacement_teacher_id=NEW.teacher_id OR NEW.resolved_at IS NULL) THEN
    RAISE EXCEPTION 'A resolved substitution requires a different teacher' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_v33_case_owner BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_substitution_case
FOR EACH ROW EXECUTE FUNCTION guard_v33_substitution_case();

CREATE VIEW exam_plan_effective_duty AS
SELECT a.id,a.org_id,a.revision_id,a.requirement_id,
       coalesce(e.teacher_id,a.teacher_id) teacher_id,a.teacher_id original_teacher_id,
       coalesce(e.engagement_no,0::bigint) engagement_no,a.status assignment_status,
       CASE WHEN a.status='CANCELLED' THEN 'CANCELLED'
            ELSE coalesce(r.status,CASE WHEN e.engagement_no IS NOT NULL THEN 'PENDING_CONFIRMATION'
                 WHEN a.status IN ('DRAFT','PENDING_CONFIRMATION') THEN 'PENDING_CONFIRMATION'
                 WHEN a.status='DECLINED' THEN 'REJECTED' ELSE a.status END) END status,
       r.reason,r.responded_at,r.checked_in_at,a.source,a.version,a.duty_role,a.starts_at,a.ends_at
FROM exam_plan_duty_assignment a
LEFT JOIN LATERAL (
  SELECT x.teacher_id,x.engagement_no FROM exam_plan_duty_engagement x
  WHERE x.org_id=a.org_id AND x.assignment_id=a.id ORDER BY x.engagement_no DESC LIMIT 1
) e ON true
LEFT JOIN exam_plan_duty_response r ON r.org_id=a.org_id AND r.assignment_id=a.id
  AND r.engagement_no=coalesce(e.engagement_no,0::bigint);
