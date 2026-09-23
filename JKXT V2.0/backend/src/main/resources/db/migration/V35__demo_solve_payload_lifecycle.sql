-- Recovery input is immutable while owned; a released owner retains provenance, not business data.
ALTER TABLE demo_solve_job ALTER COLUMN fixture_json DROP NOT NULL;
ALTER TABLE demo_solve_job ALTER COLUMN policy_json DROP NOT NULL;
ALTER TABLE demo_solve_job ADD COLUMN payload_scrubbed_at timestamptz;
ALTER TABLE demo_solve_job ADD CONSTRAINT demo_solve_payload_lifecycle CHECK (
  (fixture_json IS NOT NULL AND policy_json IS NOT NULL AND payload_scrubbed_at IS NULL)
  OR (fixture_json IS NULL AND policy_json IS NULL AND payload_scrubbed_at IS NOT NULL
      AND released_at IS NOT NULL AND state IN ('SUCCEEDED','FAILED','INFEASIBLE','CANCELLED'))
);

CREATE OR REPLACE FUNCTION protect_demo_solve_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Demo solve ownership tombstones must be retained' USING ERRCODE='23514'; END IF;
  IF ROW(NEW.reservation_id,NEW.workspace_id,NEW.generation,NEW.org_id,NEW.run_id,NEW.task_id,
      NEW.owner_boot_token,NEW.admitted_tick,NEW.admitted_at,NEW.deadline_at,NEW.lease_expires_at)
      IS DISTINCT FROM ROW(OLD.reservation_id,OLD.workspace_id,OLD.generation,OLD.org_id,OLD.run_id,OLD.task_id,
      OLD.owner_boot_token,OLD.admitted_tick,OLD.admitted_at,OLD.deadline_at,OLD.lease_expires_at)
      OR (OLD.released_at IS NOT NULL AND
          (to_jsonb(NEW)-'fixture_json'-'policy_json'-'payload_scrubbed_at') IS DISTINCT FROM
          (to_jsonb(OLD)-'fixture_json'-'policy_json'-'payload_scrubbed_at')) THEN
    RAISE EXCEPTION 'Demo solve ownership is immutable' USING ERRCODE='23514';
  END IF;
  IF ROW(NEW.fixture_json,NEW.policy_json,NEW.payload_scrubbed_at)
      IS DISTINCT FROM ROW(OLD.fixture_json,OLD.policy_json,OLD.payload_scrubbed_at) THEN
    IF OLD.fixture_json IS NULL OR OLD.policy_json IS NULL
        OR NEW.fixture_json IS NOT NULL OR NEW.policy_json IS NOT NULL
        OR NEW.payload_scrubbed_at IS NULL OR NEW.released_at IS NULL
        OR NEW.state NOT IN ('SUCCEEDED','FAILED','INFEASIBLE','CANCELLED') THEN
      RAISE EXCEPTION 'Demo solve payload may only be irreversibly scrubbed after release' USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

-- Covers historical DONE generations too; release is the existing affirmative lifecycle boundary.
UPDATE demo_solve_job SET fixture_json=NULL,policy_json=NULL,payload_scrubbed_at=now()
  WHERE released_at IS NOT NULL;
