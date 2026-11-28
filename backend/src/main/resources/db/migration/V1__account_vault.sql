CREATE TABLE pocket_account (
    account_id UUID PRIMARY KEY,
    login_tag BYTEA NOT NULL UNIQUE CHECK (octet_length(login_tag) = 32),
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    reauth_required BOOLEAN NOT NULL DEFAULT FALSE,
    credential_generation BIGINT NOT NULL CHECK (credential_generation > 0),
    gate_revision BIGINT NOT NULL CHECK (gate_revision > 0),
    pending_attempt_id UUID,
    envelope_format SMALLINT NOT NULL CHECK (envelope_format = 1),
    payload_nonce BYTEA NOT NULL CHECK (octet_length(payload_nonce) = 12),
    payload_ciphertext BYTEA NOT NULL CHECK (octet_length(payload_ciphertext) >= 16),
    key_version VARCHAR(64) NOT NULL,
    wrapped_dek BYTEA NOT NULL CHECK (octet_length(wrapped_dek) > 0)
);

CREATE INDEX ix_account_key_version ON pocket_account (key_version, account_id);

CREATE TABLE vault_policy (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    write_key_version VARCHAR(64) NOT NULL
);

CREATE SCHEMA IF NOT EXISTS pocket_audit;

CREATE TABLE pocket_audit.event (
    event_id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_id UUID NOT NULL,
    action_code VARCHAR(32) NOT NULL CHECK (
        action_code IN (
            'LOOKUP',
            'DISABLE',
            'RESTORE',
            'REVOKE_ALL',
            'ROTATE_KEY',
            'READ_AUDIT',
            'UNBIND',
            'TRIP_REAUTH',
            'UPDATE_CREDENTIAL'
        )
    ),
    result_code VARCHAR(16) NOT NULL CHECK (
        result_code IN ('OK', 'DENIED', 'CONFLICT', 'FAILED', 'PARTIAL')
    ),
    actor_ref BYTEA CHECK (octet_length(actor_ref) = 32),
    target_ref BYTEA CHECK (octet_length(target_ref) = 32)
);

CREATE INDEX ix_audit_time ON pocket_audit.event (occurred_at, event_id);
