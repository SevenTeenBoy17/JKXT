-- Committed DB selectors authorize Redis payloads; uncommitted/orphan payloads grant nothing.
CREATE TABLE portal_session_lineage (
  id uuid PRIMARY KEY,
  purpose varchar(16) NOT NULL CHECK (purpose IN ('SCHOOL','PLATFORM','DEMO')),
  current_session_id uuid,
  expires_at timestamptz NOT NULL,
  UNIQUE (id,purpose)
);
CREATE TABLE portal_session_registry (
  id uuid PRIMARY KEY,
  purpose varchar(16) NOT NULL,
  lineage_id uuid NOT NULL,
  -- Retain predecessor aliases after account deletion, bounded by original cookie expiry.
  user_id uuid NOT NULL,
  auth_version bigint NOT NULL CHECK (auth_version >= 0),
  expires_at timestamptz NOT NULL,
  UNIQUE (id,purpose,lineage_id),
  FOREIGN KEY (lineage_id,purpose) REFERENCES portal_session_lineage(id,purpose) ON DELETE CASCADE
);
ALTER TABLE portal_session_lineage ADD CONSTRAINT portal_session_current_fk
  FOREIGN KEY (current_session_id,purpose,id)
  REFERENCES portal_session_registry(id,purpose,lineage_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX portal_session_registry_user_idx ON portal_session_registry(user_id);
CREATE INDEX portal_session_registry_expiry_idx ON portal_session_registry(expires_at,id);
CREATE INDEX portal_session_lineage_expiry_idx ON portal_session_lineage(expires_at,id);
