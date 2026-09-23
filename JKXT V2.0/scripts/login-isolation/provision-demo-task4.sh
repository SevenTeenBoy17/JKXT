#!/bin/sh
set -eu
# External test provisioning only. Never sourced by the application.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=runtime_password="$JKXT_DEMO_TEST_PASSWORD" <<'SQL'
CREATE ROLE jkxt_test_demo_app LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
REVOKE ALL ON DATABASE jkxt_test_demo_entry20260920v2 FROM PUBLIC;
GRANT CONNECT ON DATABASE jkxt_test_demo_entry20260920v2 TO jkxt_test_demo_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE,CREATE ON SCHEMA public TO jkxt_test_demo_app;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE SCHEMA jkxt_environment;
CREATE TABLE jkxt_environment.identity(environment_id uuid PRIMARY KEY,purpose text NOT NULL);
INSERT INTO jkxt_environment.identity VALUES ('508d56a8-e7b0-41b3-a930-4312388df510','demo');
REVOKE ALL ON SCHEMA jkxt_environment FROM PUBLIC;
REVOKE ALL ON jkxt_environment.identity FROM PUBLIC;
GRANT USAGE ON SCHEMA jkxt_environment TO jkxt_test_demo_app;
GRANT SELECT ON jkxt_environment.identity TO jkxt_test_demo_app;
SQL
