-- Ownership outlives business-row cleanup. No schedule_run or organization cascade is allowed.
CREATE TABLE demo_solve_job (
  reservation_id uuid PRIMARY KEY,
  workspace_id uuid NOT NULL, generation bigint NOT NULL, org_id uuid NOT NULL,
  run_id uuid NOT NULL UNIQUE, task_id uuid NOT NULL,
  owner_boot_token uuid NOT NULL, admitted_tick bigint NOT NULL,
  admitted_at timestamptz NOT NULL, deadline_at timestamptz NOT NULL, lease_expires_at timestamptz NOT NULL,
  fixture_json jsonb NOT NULL, policy_json jsonb NOT NULL,
  state varchar(24) NOT NULL CHECK (state IN ('QUEUED','RUNNING','CANCEL_REQUESTED','SUCCEEDED','FAILED','INFEASIBLE','CANCELLED')),
  stop_requested_at timestamptz, native_exited_at timestamptz, released_at timestamptz,
  terminal_reason varchar(64),
  FOREIGN KEY (workspace_id,generation,org_id) REFERENCES demo_generation(workspace_id,generation,org_id),
  CHECK (deadline_at>admitted_at)
);
CREATE UNIQUE INDEX demo_solve_one_occupant ON demo_solve_job(workspace_id) WHERE released_at IS NULL;
CREATE INDEX demo_solve_dispatch ON demo_solve_job(owner_boot_token,state) WHERE released_at IS NULL;
CREATE FUNCTION protect_demo_solve_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Demo solve ownership tombstones must be retained' USING ERRCODE='23514'; END IF;
  IF ROW(NEW.reservation_id,NEW.workspace_id,NEW.generation,NEW.org_id,NEW.run_id,NEW.task_id,
      NEW.owner_boot_token,NEW.admitted_tick,NEW.admitted_at,NEW.deadline_at,NEW.lease_expires_at,NEW.fixture_json,NEW.policy_json)
      IS DISTINCT FROM ROW(OLD.reservation_id,OLD.workspace_id,OLD.generation,OLD.org_id,OLD.run_id,OLD.task_id,
      OLD.owner_boot_token,OLD.admitted_tick,OLD.admitted_at,OLD.deadline_at,OLD.lease_expires_at,OLD.fixture_json,OLD.policy_json)
      OR (OLD.released_at IS NOT NULL AND NEW IS DISTINCT FROM OLD) THEN
    RAISE EXCEPTION 'Demo solve ownership is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER demo_solve_immutable BEFORE UPDATE OR DELETE ON demo_solve_job
  FOR EACH ROW EXECUTE FUNCTION protect_demo_solve_identity();
