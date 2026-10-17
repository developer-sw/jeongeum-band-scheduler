#!/bin/sh
# Runs once on an EMPTY, dedicated production/QA volume. Never mutates an existing volume.
(
set -eu

case "${APP_DB_USERNAME:-}" in
    ''|postgres|*[!a-zA-Z0-9_]*) echo 'Invalid application database role' >&2; exit 1 ;;
esac
case "${POSTGRES_DB:-}" in
    ''|*[!a-zA-Z0-9_]*) echo 'Invalid application database name' >&2; exit 1 ;;
esac
[ -r "${APP_DB_PASSWORD_FILE:-}" ] || { echo 'Application database secret is unreadable' >&2; exit 1; }
APP_DB_PASSWORD=$(cat "$APP_DB_PASSWORD_FILE")
[ -n "$APP_DB_PASSWORD" ] || { echo 'Application database secret is empty' >&2; exit 1; }
export APP_DB_PASSWORD

psql --no-password --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv app_user APP_DB_USERNAME
\getenv app_password APP_DB_PASSWORD
\getenv app_database POSTGRES_DB
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD %L', :'app_user', :'app_password') \gexec
SELECT format('ALTER DATABASE %I OWNER TO %I', :'app_database', :'app_user') \gexec
ALTER SCHEMA public OWNER TO :"app_user";
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'app_database') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'app_database', :'app_user') \gexec
SQL
unset APP_DB_PASSWORD
)
