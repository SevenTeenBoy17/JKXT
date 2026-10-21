ALTER TABLE app_user ADD COLUMN auth_version bigint NOT NULL DEFAULT 0;

-- The database owns credential invalidation, including administrative SQL updates.
CREATE FUNCTION jkxt_bump_auth_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  -- Detached ORM entities and ordinary profile saves must not roll back a
  -- credential/permission revocation committed since that entity was read.
  NEW.auth_version := GREATEST(OLD.auth_version, NEW.auth_version);
  IF NEW.password_hash IS DISTINCT FROM OLD.password_hash
     OR NEW.status IS DISTINCT FROM OLD.status
     OR NEW.role IS DISTINCT FROM OLD.role
     OR NEW.username IS DISTINCT FROM OLD.username
     OR NEW.org_id IS DISTINCT FROM OLD.org_id
     OR NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
     OR NEW.must_change_password IS DISTINCT FROM OLD.must_change_password THEN
    NEW.auth_version := GREATEST(OLD.auth_version + 1, NEW.auth_version);
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER app_user_auth_version BEFORE UPDATE ON app_user
FOR EACH ROW EXECUTE FUNCTION jkxt_bump_auth_version();

-- System-role fallback can serve users in other organizations, so invalidate the
-- same role everywhere. A permission reduction must never leave cached grants live.
CREATE FUNCTION jkxt_revoke_changed_role_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'UPDATE' AND NEW.permissions IS NOT DISTINCT FROM OLD.permissions
     AND NEW.code IS NOT DISTINCT FROM OLD.code
     AND NEW.org_id IS NOT DISTINCT FROM OLD.org_id
     AND NEW.system_role IS NOT DISTINCT FROM OLD.system_role THEN RETURN NEW; END IF;
  IF TG_OP <> 'INSERT' THEN
    UPDATE app_user SET auth_version = auth_version + 1 WHERE role = OLD.code;
  END IF;
  IF TG_OP <> 'DELETE' THEN
    UPDATE app_user SET auth_version = auth_version + 1 WHERE role = NEW.code;
    RETURN NEW;
  END IF;
  RETURN OLD;
END;
$$;
CREATE TRIGGER role_auth_version AFTER INSERT OR UPDATE OR DELETE ON role
FOR EACH ROW EXECUTE FUNCTION jkxt_revoke_changed_role_sessions();

CREATE FUNCTION jkxt_revoke_suspended_organization_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status AND NEW.status <> 'active' THEN
    UPDATE app_user SET auth_version = auth_version + 1
      WHERE org_id = NEW.id AND role <> 'SUPER_ADMIN';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER organization_auth_version AFTER UPDATE OF status ON organization
FOR EACH ROW EXECUTE FUNCTION jkxt_revoke_suspended_organization_sessions();
