Test-only TLS fixtures for VaultTransitKeyWrapperTest (never used outside tests).

- `good.p12` / `good.pem`: self-signed EC certificate for `localhost` and `127.0.0.1` (store password `changeit`).
- `other.p12` / `other.pem`: certificate for `vault.invalid` only, to test host name verification.

Regenerate with `keytool -genkeypair -keyalg EC -groupname secp256r1 -validity 36500 -storetype PKCS12 ...`.
