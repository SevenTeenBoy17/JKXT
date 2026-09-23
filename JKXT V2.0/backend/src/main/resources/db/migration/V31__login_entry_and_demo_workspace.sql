-- Additive upgrade. Existing roles, grants, schools and accounts are not rewritten.
ALTER TABLE app_user ADD COLUMN auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0);
ALTER TABLE app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE app_user ADD CONSTRAINT app_user_role_check
  CHECK (role IN ('SUPER_ADMIN','ADMIN','VIEWER','TEACHER_QUERY','DEMO'));
CREATE FUNCTION increment_app_user_auth_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.password_hash, NEW.role, NEW.org_id, NEW.status, NEW.deleted_at)
      IS DISTINCT FROM ROW(OLD.password_hash, OLD.role, OLD.org_id, OLD.status, OLD.deleted_at) THEN
    NEW.auth_version := OLD.auth_version + 1;
  ELSE
    NEW.auth_version := OLD.auth_version;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_auth_version_changed BEFORE UPDATE ON app_user
  FOR EACH ROW EXECUTE FUNCTION increment_app_user_auth_version();

CREATE TABLE demo_workspace (
  id uuid PRIMARY KEY, visitor_id uuid NOT NULL, org_id uuid NOT NULL UNIQUE,
  generation bigint NOT NULL CHECK (generation > 0),
  state varchar(16) NOT NULL CHECK (state IN ('READY','RESETTING','FAILED','EXPIRED')),
  created_at timestamptz NOT NULL, expires_at timestamptz NOT NULL,
  last_activity_at timestamptz NOT NULL, cleanup_after timestamptz,
  CHECK (expires_at > created_at)
);
CREATE UNIQUE INDEX demo_one_live_workspace_per_visitor ON demo_workspace(visitor_id) WHERE state <> 'EXPIRED';

-- org_id/user_id are durable provenance UUIDs, intentionally NOT cascading FKs:
-- cleanup deletes tenant data while retaining ownership and operation tombstones.
CREATE TABLE demo_generation (
  workspace_id uuid NOT NULL REFERENCES demo_workspace(id) DEFERRABLE INITIALLY DEFERRED,
  generation bigint NOT NULL CHECK (generation > 0),
  org_id uuid NOT NULL UNIQUE, user_id uuid NOT NULL UNIQUE,
  cleanup_state varchar(16) NOT NULL CHECK (cleanup_state IN ('LIVE','PENDING','RUNNING','DONE','FAILED')),
  PRIMARY KEY (workspace_id,generation), UNIQUE (workspace_id,generation,org_id)
);
ALTER TABLE demo_workspace ADD CONSTRAINT demo_workspace_current_generation_fk
  FOREIGN KEY (id,generation,org_id) REFERENCES demo_generation(workspace_id,generation,org_id)
  DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE demo_operation (
  workspace_id uuid NOT NULL REFERENCES demo_workspace(id),
  expected_generation bigint NOT NULL CHECK (expected_generation > 0),
  operation_id uuid NOT NULL,
  kind varchar(16) NOT NULL CHECK (kind IN ('CREATE','RESET')),
  request_hash varchar(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  state varchar(16) NOT NULL CHECK (state IN ('RUNNING','SUCCEEDED','FAILED')),
  result_json jsonb,
  PRIMARY KEY (workspace_id,operation_id),
  FOREIGN KEY (workspace_id,expected_generation) REFERENCES demo_generation(workspace_id,generation)
    DEFERRABLE INITIALLY DEFERRED
);

CREATE FUNCTION protect_demo_generation_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'Demo ownership tombstones must be retained' USING ERRCODE='23514';
  END IF;
  IF ROW(NEW.workspace_id,NEW.generation,NEW.org_id,NEW.user_id)
      IS DISTINCT FROM ROW(OLD.workspace_id,OLD.generation,OLD.org_id,OLD.user_id) THEN
    RAISE EXCEPTION 'Demo generation ownership is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER demo_generation_immutable BEFORE UPDATE OR DELETE ON demo_generation
  FOR EACH ROW EXECUTE FUNCTION protect_demo_generation_identity();

-- Deferred checks let the trusted demo service insert user and generation in either
-- order in ONE transaction. Ordinary account creation cannot commit a DEMO user.
CREATE FUNCTION check_demo_generation_user() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM app_user u JOIN organization o ON o.id=u.org_id
      WHERE u.id=NEW.user_id AND u.org_id=NEW.org_id AND u.role='DEMO') THEN
    RAISE EXCEPTION 'Demo generation requires a matching DEMO user and organization' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER demo_generation_user_valid AFTER INSERT ON demo_generation
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_demo_generation_user();

CREATE FUNCTION check_demo_user_generation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_user_row app_user%ROWTYPE;
BEGIN
  SELECT * INTO current_user_row FROM app_user WHERE id=NEW.id;
  IF NOT FOUND THEN RETURN NULL; END IF;
  IF current_user_row.role='DEMO' AND NOT EXISTS (SELECT 1 FROM demo_generation g
      WHERE g.user_id=current_user_row.id AND g.org_id=current_user_row.org_id) THEN
    RAISE EXCEPTION 'DEMO accounts require service-created generation ownership' USING ERRCODE='23514';
  END IF;
  IF EXISTS (SELECT 1 FROM demo_generation g WHERE g.user_id=current_user_row.id
      AND (g.org_id IS DISTINCT FROM current_user_row.org_id OR current_user_row.role<>'DEMO')) THEN
    RAISE EXCEPTION 'Demo user ownership and role cannot be reassigned' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER demo_user_generation_valid AFTER INSERT OR UPDATE ON app_user
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_demo_user_generation();

CREATE FUNCTION protect_demo_user_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.id IS DISTINCT FROM OLD.id AND EXISTS (SELECT 1 FROM demo_generation WHERE user_id=OLD.id) THEN
    RAISE EXCEPTION 'Demo user primary identity is immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER demo_user_identity BEFORE UPDATE OF id ON app_user
  FOR EACH ROW EXECUTE FUNCTION protect_demo_user_identity();

CREATE FUNCTION protect_demo_workspace_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id,NEW.visitor_id,NEW.created_at,NEW.expires_at)
      IS DISTINCT FROM ROW(OLD.id,OLD.visitor_id,OLD.created_at,OLD.expires_at)
      OR NEW.generation < OLD.generation
      OR (OLD.state='EXPIRED' AND NEW.state<>'EXPIRED') THEN
    RAISE EXCEPTION 'Demo identity, absolute expiry and expired terminal state are immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER demo_workspace_identity BEFORE UPDATE ON demo_workspace
  FOR EACH ROW EXECUTE FUNCTION protect_demo_workspace_identity();

CREATE FUNCTION protect_demo_operation_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.workspace_id,NEW.expected_generation,NEW.operation_id,NEW.kind,NEW.request_hash)
      IS DISTINCT FROM ROW(OLD.workspace_id,OLD.expected_generation,OLD.operation_id,OLD.kind,OLD.request_hash) THEN
    RAISE EXCEPTION 'Idempotent operation identity and request digest are immutable' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER demo_operation_identity BEFORE UPDATE ON demo_operation
  FOR EACH ROW EXECUTE FUNCTION protect_demo_operation_identity();
