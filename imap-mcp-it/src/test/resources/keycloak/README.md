Test-only realms for `KeycloakFixture` (never used outside tests); one Keycloak container imports all three.

- `imap-realm.json` (realm `imap`): the linking surface (C1). The client accepts any redirect URI because the tests run
  the server on a free port; users `alice` and `carol` (role `imap-user`) and `bob` (no role). Its issuer is derived from
  the request host.
- `imap-public-realm.json` (realm `imap-public`) and `imap-other-realm.json` (realm `imap-other`): the `http-public`
  resource server (B6). Public test clients issue access tokens by the password grant. The realm attribute
  `frontendUrl` is `https://idp.example.com`, so the tokens carry that https issuer while the tests reach the container
  over plain http on its mapped port.
- `imap-realm.json` also holds the upstream OAuth side of the XOAUTH2 ITs (#500): the realm has "Revoke Refresh Token"
  on with max reuse 0 (strict rotation and reuse detection; realm-wide in Keycloak, no test of this realm refreshes
  otherwise), and the confidential client `imap-mcp-upstream` (PKCE S256) is the `generic` provider. Dovecot validates
  its access tokens with the realm's public key, which the test reads from the JWKS endpoint at run time.
