-- XOAUTH2 (SPEC 5.7 "Schema V4", 5.3 "Upstream OAuth linking", 5.5). Every table that holds tenant data carries
-- tenant_id. audit_event has no CHECK on event_type or result, so the new values (credential_revoked and the link
-- results denied, provider_error, exchange_failed, probe_failed, duplicate, limit) need no schema change.

ALTER TABLE mailbox_account ADD COLUMN provider_id text;

-- NOT VALID: rows written before V4 are not checked (no code ever wrote an xoauth2 row), so the migration cannot
-- fail on old data; every insert and update is checked.
ALTER TABLE mailbox_account
    ADD CONSTRAINT mailbox_account_auth_provider CHECK ((auth = 'xoauth2') = (provider_id IS NOT NULL)) NOT VALID;

-- One oauth account per tenant, provider and address; password accounts are unchanged.
CREATE UNIQUE INDEX mailbox_account_oauth_address_unique
    ON mailbox_account (tenant_id, provider_id, lower(address)) WHERE auth = 'xoauth2';

-- The stable row locked by a refresh (5.5); the credential versions stay in the credential table.
CREATE TABLE oauth_grant (
    tenant_id       uuid        NOT NULL,
    account_id      text        NOT NULL,
    provider_id     text        NOT NULL,
    scope           text        NOT NULL,
    refresh_version integer     NOT NULL,
    refreshed_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, account_id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES mailbox_account (tenant_id, id) ON DELETE CASCADE
);

-- Upstream OAuth logins in flight, like oidc_pending: the key is the SHA-256 of state; the row cap is the
-- application's; expired rows are removed periodically.
CREATE TABLE upstream_pending (
    state_hash        bytea       PRIMARY KEY CONSTRAINT upstream_pending_state_hash_nonempty CHECK (octet_length(state_hash) > 0),
    tenant_id         uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    provider_id       text        NOT NULL,
    pkce_verifier     text        NOT NULL,
    label             text        NOT NULL,
    address           text        NOT NULL,
    read_only         boolean     NOT NULL DEFAULT true,
    relink_account_id text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    expires_at        timestamptz NOT NULL,
    FOREIGN KEY (tenant_id, relink_account_id) REFERENCES mailbox_account (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX upstream_pending_expires ON upstream_pending (expires_at);
CREATE INDEX upstream_pending_tenant ON upstream_pending (tenant_id);
