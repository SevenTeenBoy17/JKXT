CREATE TABLE IF NOT EXISTS jkxt_schema_marker (
  marker_key varchar(64) PRIMARY KEY,
  marker_value varchar(255) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO jkxt_schema_marker (marker_key, marker_value)
VALUES ('p1_baseline', 'Spring Boot skeleton initialized')
ON CONFLICT (marker_key) DO NOTHING;
