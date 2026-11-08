#!/bin/sh
# Runs once, at the first start of an empty data volume. The passwords come from the container environment
# (\getenv), never from a command line. The app schema is created by Flyway at app startup.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'EOSQL'
\getenv kc_pw KEYCLOAK_DB_PASSWORD
\getenv app_pw IMAPMCP_DB_PASSWORD
CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';
CREATE DATABASE keycloak OWNER keycloak ENCODING 'UTF8';
CREATE ROLE imapmcp LOGIN PASSWORD :'app_pw';
CREATE DATABASE imapmcp OWNER imapmcp ENCODING 'UTF8';
REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
REVOKE ALL ON DATABASE imapmcp FROM PUBLIC;
EOSQL
