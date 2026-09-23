-- Minimal immutable provenance survives old-generation business cleanup.
CREATE TABLE demo_simulation (
  workspace_id uuid NOT NULL,
  generation bigint NOT NULL,
  org_id uuid NOT NULL,
  task_id uuid NOT NULL,
  operation_id uuid NOT NULL,
  request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  label text NOT NULL CHECK (label = '模拟发布，未通知真实教师'),
  created_at timestamptz NOT NULL,
  PRIMARY KEY (workspace_id, operation_id),
  FOREIGN KEY (workspace_id,generation,org_id) REFERENCES demo_generation(workspace_id,generation,org_id)
);
CREATE FUNCTION protect_demo_simulation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Demo simulation provenance and result are immutable' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER demo_simulation_immutable BEFORE UPDATE OR DELETE ON demo_simulation
  FOR EACH ROW EXECUTE FUNCTION protect_demo_simulation();
