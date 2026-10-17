#!/bin/sh
# One PostgreSQL process, two databases, two non-administrative application roles.
set -eu
psql -X -q -v ON_ERROR_STOP=1 --username="${PGUSER:-infrastructure_admin}" --dbname=postgres <<'SQL'
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv app_password AUTH_APP_DB_PASSWORD
\getenv identity_password AUTH_IDENTITY_DB_PASSWORD
SELECT 'CREATE ROLE monitoring LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='monitoring') \gexec
SELECT 'CREATE ROLE identity LOGIN' WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='identity') \gexec
SELECT format('ALTER ROLE monitoring NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'app_password') \gexec
SELECT format('ALTER ROLE identity NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'identity_password') \gexec
SELECT 'CREATE DATABASE monitoring OWNER monitoring' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='monitoring') \gexec
SELECT 'CREATE DATABASE identity OWNER identity' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname='identity') \gexec
ALTER DATABASE monitoring OWNER TO monitoring;
ALTER DATABASE identity OWNER TO identity;
REVOKE ALL ON DATABASE monitoring FROM PUBLIC;
REVOKE ALL ON DATABASE identity FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE monitoring TO monitoring;
GRANT CONNECT, TEMPORARY ON DATABASE identity TO identity;
REVOKE ALL ON DATABASE monitoring FROM identity;
REVOKE ALL ON DATABASE identity FROM monitoring;
REVOKE CONNECT ON DATABASE postgres FROM PUBLIC;
REVOKE CONNECT ON DATABASE template1 FROM PUBLIC;
SQL
