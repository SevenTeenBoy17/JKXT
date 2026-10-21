-- Round 9, PB-10 (DATA-03): a published duty roster must show the teacher as published. The duty
-- views read teacher.name/teacher_no live, so renaming a teacher rewrote every published duty.
-- Published assignment rows are immutable (V19) and engagements append-only (V33), so the snapshot
-- lives in its own table, keyed by revision and teacher, written once and never updated:
--   * when a revision first enters PUBLISHED/EXECUTING, for every teacher on its duty assignments;
--   * when a substitution engagement is appended, for the replacement teacher.
-- Reads use coalesce(snapshot, live value), so rows without a snapshot behave exactly as before.
-- Backfill: revisions already published get a snapshot of the teacher values as they are when this
-- migration runs; names changed before V36 cannot be recovered (there is no history to recover from).
CREATE TABLE exam_plan_teacher_snapshot (
  org_id uuid NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
  revision_id uuid NOT NULL,
  teacher_id uuid NOT NULL,
  teacher_no varchar(64),
  teacher_name varchar(128) NOT NULL,
  captured_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (org_id, revision_id, teacher_id),
  FOREIGN KEY (org_id, revision_id) REFERENCES exam_plan_revision(org_id, id) ON DELETE CASCADE
);

CREATE FUNCTION guard_v36_teacher_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Published teacher snapshot is immutable' USING ERRCODE = '23514';
END $$;
CREATE TRIGGER trg_v36_teacher_snapshot_immutable BEFORE UPDATE ON exam_plan_teacher_snapshot
FOR EACH ROW EXECUTE FUNCTION guard_v36_teacher_snapshot();

CREATE FUNCTION v36_snapshot_revision_teachers() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IN ('PUBLISHED','EXECUTING')
     AND (TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
    INSERT INTO exam_plan_teacher_snapshot (org_id, revision_id, teacher_id, teacher_no, teacher_name)
    SELECT DISTINCT a.org_id, a.revision_id, t.id, t.teacher_no, t.name
    FROM exam_plan_duty_assignment a
    JOIN teacher t ON t.org_id = a.org_id AND t.id = a.teacher_id
    WHERE a.org_id = NEW.org_id AND a.revision_id = NEW.id
    ON CONFLICT DO NOTHING;
  END IF;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_v36_revision_teacher_snapshot AFTER INSERT OR UPDATE OF status ON exam_plan_revision
FOR EACH ROW EXECUTE FUNCTION v36_snapshot_revision_teachers();

CREATE FUNCTION v36_snapshot_engagement_teacher() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO exam_plan_teacher_snapshot (org_id, revision_id, teacher_id, teacher_no, teacher_name)
  SELECT a.org_id, a.revision_id, t.id, t.teacher_no, t.name
  FROM exam_plan_duty_assignment a
  JOIN teacher t ON t.org_id = NEW.org_id AND t.id = NEW.teacher_id
  WHERE a.org_id = NEW.org_id AND a.id = NEW.assignment_id
  ON CONFLICT DO NOTHING;
  RETURN NULL;
END $$;
CREATE TRIGGER trg_v36_engagement_teacher_snapshot AFTER INSERT ON exam_plan_duty_engagement
FOR EACH ROW EXECUTE FUNCTION v36_snapshot_engagement_teacher();

INSERT INTO exam_plan_teacher_snapshot (org_id, revision_id, teacher_id, teacher_no, teacher_name)
SELECT DISTINCT a.org_id, a.revision_id, t.id, t.teacher_no, t.name
FROM exam_plan_duty_assignment a
JOIN exam_plan_revision r ON r.org_id = a.org_id AND r.id = a.revision_id
JOIN teacher t ON t.org_id = a.org_id AND t.id = a.teacher_id
WHERE r.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
ON CONFLICT DO NOTHING;

INSERT INTO exam_plan_teacher_snapshot (org_id, revision_id, teacher_id, teacher_no, teacher_name)
SELECT DISTINCT a.org_id, a.revision_id, t.id, t.teacher_no, t.name
FROM exam_plan_duty_engagement e
JOIN exam_plan_duty_assignment a ON a.org_id = e.org_id AND a.id = e.assignment_id
JOIN exam_plan_revision r ON r.org_id = a.org_id AND r.id = a.revision_id
JOIN teacher t ON t.org_id = e.org_id AND t.id = e.teacher_id
WHERE r.status IN ('PUBLISHED','EXECUTING','CLOSED','ARCHIVED')
ON CONFLICT DO NOTHING;
