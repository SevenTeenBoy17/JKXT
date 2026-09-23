-- Reproducible solver provenance and immutable version-scoped JOINT inputs.

ALTER TABLE exam_plan_planning_job
  ADD COLUMN input_hash varchar(64),
  ADD COLUMN result_hash varchar(64),
  ADD COLUMN solver_version varchar(80),
  ADD COLUMN iteration_count int,
  ADD COLUMN request_hash varchar(64),
  ADD CONSTRAINT chk_v28_job_input_hash CHECK (input_hash IS NULL OR input_hash ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT chk_v28_job_result_hash CHECK (result_hash IS NULL OR result_hash ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT chk_v28_job_request_hash CHECK (request_hash IS NULL OR request_hash ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT chk_v28_job_solver_version CHECK (
    solver_version IS NULL OR nullif(btrim(solver_version), '') IS NOT NULL
  ),
  ADD CONSTRAINT chk_v28_job_iteration_count CHECK (iteration_count IS NULL OR iteration_count >= 0);

CREATE INDEX idx_v28_planning_job_input_hash
  ON exam_plan_planning_job(org_id, revision_id, input_hash)
  WHERE input_hash IS NOT NULL;
CREATE INDEX idx_v28_planning_job_request_hash
  ON exam_plan_planning_job(org_id, revision_id, request_hash)
  WHERE request_hash IS NOT NULL;

CREATE OR REPLACE FUNCTION prevent_v28_planning_provenance_rewrite()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status IN ('RUNNING','SUCCEEDED','INFEASIBLE','INVALID','FAILED','CANCELLED') AND (
    NEW.input_hash IS DISTINCT FROM OLD.input_hash OR
    NEW.request_hash IS DISTINCT FROM OLD.request_hash OR
    NEW.solver_version IS DISTINCT FROM OLD.solver_version
  ) THEN
    RAISE EXCEPTION 'Started planning job input provenance is immutable' USING ERRCODE='23514';
  END IF;
  IF OLD.status IN ('SUCCEEDED','INFEASIBLE','INVALID','FAILED','CANCELLED') AND (
    NEW.result_hash IS DISTINCT FROM OLD.result_hash OR
    NEW.iteration_count IS DISTINCT FROM OLD.iteration_count
  ) THEN
    RAISE EXCEPTION 'Finished planning job result provenance is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_v28_planning_provenance_immutable
BEFORE UPDATE ON exam_plan_planning_job
FOR EACH ROW EXECUTE FUNCTION prevent_v28_planning_provenance_rewrite();

CREATE TRIGGER trg_v28_demand_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_exam_demand
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_demand_class_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_demand_class
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_demand_window_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_demand_window
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_candidate_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_candidate
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_enrollment_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_enrollment
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_candidate_window_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_candidate_window
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_special_requirement_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_special_requirement
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_room_pool_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_room_pool
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_room_window_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_room_window
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_room_seat_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_room_seat
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_teacher_pool_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_teacher_pool
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_teacher_avoidance_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_teacher_avoidance
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
CREATE TRIGGER trg_v28_campus_travel_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_campus_travel
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();

-- The class/session bridge is revision content too; legacy output tables already have this guard.
CREATE TRIGGER trg_v28_class_session_immutable
BEFORE INSERT OR UPDATE OR DELETE ON exam_plan_class_session_assignment
FOR EACH ROW EXECUTE FUNCTION prevent_immutable_revision_child_change();
