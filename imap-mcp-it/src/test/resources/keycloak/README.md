Test-only realm for KeycloakFixture (never used outside tests). The client accepts any redirect URI because the
tests run the server on a free port; the realm holds two users: `alice` (role `imap-user`) and `bob` (no role).
