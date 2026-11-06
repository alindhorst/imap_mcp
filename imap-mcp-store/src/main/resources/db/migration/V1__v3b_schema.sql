-- v3b persistence (SPEC 5.7). Every table that holds tenant data carries tenant_id and every query filters on it.

CREATE TABLE tenant (
    id         uuid PRIMARY KEY,
    issuer     text        NOT NULL,
    subject    text        NOT NULL,
    status     text        NOT NULL DEFAULT 'active' CONSTRAINT tenant_status_valid CHECK (status IN ('active', 'disabled')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_issuer_subject UNIQUE (issuer, subject)
);

CREATE TABLE mailbox_account (
    tenant_id          uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    id                 text        NOT NULL CONSTRAINT mailbox_account_id_length CHECK (char_length(id) BETWEEN 1 AND 32),
    handle             text        NOT NULL CONSTRAINT mailbox_account_handle_unique UNIQUE,
    label              text        NOT NULL,
    address            text        NOT NULL,
    host               text        NOT NULL,
    port               integer     NOT NULL CONSTRAINT mailbox_account_port_range CHECK (port BETWEEN 1 AND 65535),
    security           text        NOT NULL,
    flavor             text        NOT NULL,
    username           text        NOT NULL,
    auth               text        NOT NULL,
    read_only          boolean     NOT NULL DEFAULT true,
    allow_purge        boolean     NOT NULL DEFAULT false,
    status             text        NOT NULL DEFAULT 'active'
        CONSTRAINT mailbox_account_status_valid CHECK (status IN ('active', 'needs_relink')),
    failed_logins      integer     NOT NULL DEFAULT 0,
    credential_version integer     NOT NULL DEFAULT 1,
    revision           bigint      NOT NULL DEFAULT 1,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, id)
);

CREATE TABLE credential (
    tenant_id   uuid        NOT NULL,
    account_id  text        NOT NULL,
    version     integer     NOT NULL,
    kind        text        NOT NULL CONSTRAINT credential_kind_valid CHECK (kind IN ('password', 'oauth_refresh')),
    kek_id      text        NOT NULL,
    wrapped_dek bytea       NOT NULL,
    nonce       bytea       NOT NULL,
    ciphertext  bytea       NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, account_id, version),
    FOREIGN KEY (tenant_id, account_id) REFERENCES mailbox_account (tenant_id, id) ON DELETE CASCADE
);

-- No foreign keys: audit rows outlive the tenant and account they describe (retention is by age, D5).
-- ref_hash is the truncated HMAC of SPEC 5.3; neither handles, folder names nor UIDs are ever stored.
CREATE TABLE audit_event (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  timestamptz NOT NULL DEFAULT now(),
    tenant_id    uuid        NOT NULL,
    account_id   text,
    request_id   text        NOT NULL,
    event_type   text        NOT NULL,
    tool         text,
    ref_hash     bytea CONSTRAINT audit_event_ref_hash_length CHECK (ref_hash IS NULL OR octet_length(ref_hash) = 16),
    ref_key_id   smallint,
    item_status  text,
    error_code   text,
    confirmation text,
    result       text,
    completed_at timestamptz
);
CREATE INDEX audit_event_tenant_time ON audit_event (tenant_id, occurred_at);
CREATE INDEX audit_event_time ON audit_event (occurred_at);

CREATE TABLE web_session (
    id_hash     bytea       PRIMARY KEY CONSTRAINT web_session_id_hash_nonempty CHECK (octet_length(id_hash) > 0),
    tenant_id   uuid        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    csrf_secret bytea       NOT NULL,
    auth_time   timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);
CREATE INDEX web_session_expires ON web_session (expires_at);
CREATE INDEX web_session_tenant ON web_session (tenant_id);

CREATE TABLE oidc_pending (
    state_hash    bytea       PRIMARY KEY CONSTRAINT oidc_pending_state_hash_nonempty CHECK (octet_length(state_hash) > 0),
    nonce         text        NOT NULL,
    pkce_verifier text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL
);
CREATE INDEX oidc_pending_expires ON oidc_pending (expires_at);
