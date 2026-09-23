-- Published state is monotonic. A live plan/revision may advance to execution/closure/archive, but
-- must never be silently rewritten as draft, failed, cancelled or review-required.

CREATE OR REPLACE FUNCTION enforce_exam_plan_status_monotonic()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('EXECUTING','CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Published exam plan status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'EXECUTING' AND NEW.status NOT IN ('CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Executing exam plan status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'CLOSED' AND NEW.status <> 'ARCHIVED' THEN
      RAISE EXCEPTION 'Closed exam plan status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'ARCHIVED' THEN
      RAISE EXCEPTION 'Archived exam plan status is final' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_v21_exam_plan_status_monotonic
BEFORE UPDATE OF status ON exam_plan
FOR EACH ROW EXECUTE FUNCTION enforce_exam_plan_status_monotonic();

CREATE OR REPLACE FUNCTION enforce_plan_revision_status_monotonic()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF OLD.status = 'PUBLISHED' AND NEW.status NOT IN ('EXECUTING','CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Published plan revision status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'EXECUTING' AND NEW.status NOT IN ('CLOSED','ARCHIVED') THEN
      RAISE EXCEPTION 'Executing plan revision status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'CLOSED' AND NEW.status <> 'ARCHIVED' THEN
      RAISE EXCEPTION 'Closed plan revision status cannot move backward' USING ERRCODE = '23514';
    ELSIF OLD.status = 'ARCHIVED' THEN
      RAISE EXCEPTION 'Archived plan revision status is final' USING ERRCODE = '23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_v21_plan_revision_status_monotonic
BEFORE UPDATE OF status ON exam_plan_revision
FOR EACH ROW EXECUTE FUNCTION enforce_plan_revision_status_monotonic();
