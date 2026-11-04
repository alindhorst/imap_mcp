# Dovecot test fixture

`tls.crt` / `tls.key` are a committed, self-signed, test-only certificate and key (never use them anywhere real).
Subject alternative names: `DNS:dovecot` (container network alias, used by `DockerImageIT`), `DNS:localhost`,
`IP:127.0.0.1`. Hostname verification stays on in every IT.

Regenerate (RSA 2048, SHA-256, 10 years; `TestTrustTest` warns a year before expiry):

    openssl req -x509 -newkey rsa:2048 -nodes -keyout tls.key -out tls.crt -days 3650 -sha256 -subj "/CN=localhost" -addext "subjectAltName=DNS:dovecot,DNS:localhost,IP:127.0.0.1"
