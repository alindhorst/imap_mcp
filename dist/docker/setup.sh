#!/usr/bin/env bash
# Prepares the test environment: .env with generated secrets (mode 0600, never overwritten), app/config.yaml from
# config.yaml.example (never overwritten), and with --local a local CA, a leaf certificate and the app truststore.
#
#   MCP_HOST=mail.example.org IDP_HOST=idp.example.org ACME_EMAIL=me@example.org IMAP_HOSTS=imap.example.org ./setup.sh
#   ./setup.sh --local            # .test names on port 8443, local CA; no DNS, no Let's Encrypt
#
# Secrets are never printed and never put on a command line.
set -euo pipefail
cd "$(dirname "$0")"
umask 077

LOCAL=false
[ "${1:-}" = "--local" ] && LOCAL=true

if $LOCAL; then
  MCP_HOST=${MCP_HOST:-mail.imap-mcp.test}
  IDP_HOST=${IDP_HOST:-idp.imap-mcp.test}
  HTTPS_PORT=${HTTPS_PORT:-8443}
  HTTP_PORT=${HTTP_PORT:-8081}
  CADDY_SITE=local
  ACME_EMAIL=${ACME_EMAIL:-}
else
  : "${MCP_HOST:?set MCP_HOST (public name of the MCP server)}"
  : "${IDP_HOST:?set IDP_HOST (public name of Keycloak)}"
  : "${ACME_EMAIL:?set ACME_EMAIL (contact address for ACME)}"
  HTTPS_PORT=${HTTPS_PORT:-443}
  HTTP_PORT=${HTTP_PORT:-80}
  CADDY_SITE=public
fi
IMAP_HOSTS=${IMAP_HOSTS:-imap.example.com}

# The values go into .env and into sed expressions: refuse anything but plain host names and an address
# without whitespace, | or &, before anything is written.
HOST_RE='^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'
check_host() { [[ "$2" =~ $HOST_RE ]] || { echo "setup.sh: $1 is not a lower-case host name" >&2; exit 1; }; }
check_host MCP_HOST "$MCP_HOST"
check_host IDP_HOST "$IDP_HOST"
IFS=',' read -r -a imap_list <<< "$IMAP_HOSTS"
for h in "${imap_list[@]}"; do
  h=${h//[[:space:]]/}
  check_host IMAP_HOSTS "${h#\*.}"
done
case "$ACME_EMAIL" in
  *[[:space:]\|\&]*) echo "setup.sh: ACME_EMAIL must not contain whitespace, | or &" >&2; exit 1 ;;
esac
case "$HTTPS_PORT$HTTP_PORT" in
  *[!0-9]*) echo "setup.sh: HTTPS_PORT and HTTP_PORT must be numbers" >&2; exit 1 ;;
esac
suffix=""
[ "$HTTPS_PORT" != "443" ] && suffix=":$HTTPS_PORT"

# base64url without padding of 32 random bytes (the format of HANDLE_KEY_1 and KEK_1)
key32() { openssl rand 32 | basenc --base64url | tr -d '=\n'; }
hex32() { openssl rand -hex 32; }

if [ -e .env ]; then
  echo ".env exists: left unchanged"
else
  {
    echo "MCP_HOST=$MCP_HOST"
    echo "IDP_HOST=$IDP_HOST"
    echo "MCP_ORIGIN=https://$MCP_HOST$suffix"
    echo "IDP_ORIGIN=https://$IDP_HOST$suffix"
    echo "ACME_EMAIL=$ACME_EMAIL"
    echo "CADDY_SITE=$CADDY_SITE"
    echo "HTTPS_PORT=$HTTPS_PORT"
    echo "HTTP_PORT=$HTTP_PORT"
    echo "APP_PORT=${APP_PORT:-8080}"
    if $LOCAL; then
      echo "APP_JDK_JAVA_OPTIONS=-Djavax.net.ssl.trustStore=/truststore/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit"
    else
      echo "APP_JDK_JAVA_OPTIONS="
    fi
    echo "POSTGRES_PASSWORD=$(hex32)"
    echo "KEYCLOAK_DB_PASSWORD=$(hex32)"
    echo "IMAPMCP_DB_PASSWORD=$(hex32)"
    echo "KEYCLOAK_ADMIN_PASSWORD=$(hex32)"
    echo "WEB_CLIENT_SECRET=$(hex32)"
    echo "HANDLE_KEY_1=$(key32)"
    echo "KEK_1=$(key32)"
  } > .env
  chmod 600 .env
  echo ".env written (mode 0600)"
fi

if [ -e app/config.yaml ]; then
  echo "app/config.yaml exists: left unchanged"
else
  hosts=$(printf '%s' "$IMAP_HOSTS" | sed -e 's/[[:space:]]//g' -e 's/,/", "/g')
  sed -e "s|idp\.example\.com|$IDP_HOST$suffix|g" \
      -e "s|mail\.example\.com|$MCP_HOST$suffix|g" \
      -e "s|allowedHosts: \[\"imap\.example\.com\"\]|allowedHosts: [\"$hosts\"]|" \
      config.yaml.example > app/config.yaml
  chmod 644 app/config.yaml
  echo "app/config.yaml written"
fi

if $LOCAL; then
  mkdir -p local-ca caddy/local/certs truststore
  chmod 755 caddy/local/certs
  if [ -e local-ca/ca.crt ]; then
    echo "local CA exists: left unchanged"
  else
    openssl req -x509 -newkey rsa:3072 -nodes -days 365 -subj "/CN=imap-mcp local test CA" \
      -keyout local-ca/ca.key -out local-ca/ca.crt 2>/dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$MCP_HOST" -keyout caddy/local/certs/leaf.key \
      -out local-ca/leaf.csr 2>/dev/null
    printf 'subjectAltName=DNS:%s,DNS:%s\nextendedKeyUsage=serverAuth\n' "$MCP_HOST" "$IDP_HOST" > local-ca/leaf.ext
    openssl x509 -req -in local-ca/leaf.csr -CA local-ca/ca.crt -CAkey local-ca/ca.key -CAcreateserial -days 90 \
      -extfile local-ca/leaf.ext -out caddy/local/certs/leaf.crt 2>/dev/null
    # the proxy container reads the key of this throwaway test certificate as another uid
    chmod 644 caddy/local/certs/leaf.key caddy/local/certs/leaf.crt local-ca/ca.crt
    echo "local CA and leaf certificate written (local-ca/, caddy/local/certs/)"
  fi
  if [ -e truststore/truststore.p12 ]; then
    echo "truststore exists: left unchanged"
  else
    ENGINE=${ENGINE:-$(command -v podman || command -v docker)}
    base=$(grep -m1 '^FROM ' Dockerfile | cut -d' ' -f2)
    # the JDK default trust store plus the local CA, so that outbound IMAP over TLS keeps working
    "$ENGINE" run --rm --user 0 -v "$PWD/local-ca:/ca:ro,Z" -v "$PWD/truststore:/out:Z" --entrypoint sh "$base" -c '
      cp "$JAVA_HOME/lib/security/cacerts" /out/truststore.p12 &&
      keytool -importcert -noprompt -alias imap-mcp-local-ca -file /ca/ca.crt \
        -keystore /out/truststore.p12 -storepass changeit >/dev/null &&
      chmod 644 /out/truststore.p12'
    echo "truststore/truststore.p12 written (public CA certificate only)"
  fi
fi

echo "next: copy the jar to jar/imap-mcp.jar, then see README.md"
