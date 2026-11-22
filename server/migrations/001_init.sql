-- UPC Pocket multi-user web service schema.
-- Applied idempotently at startup when DB_AUTO_MIGRATE=true.

CREATE TABLE IF NOT EXISTS users (
  id uuid PRIMARY KEY,
  identity_hmac bytea NOT NULL UNIQUE,
  consent_version text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz,
  deleted_at timestamptz
);

CREATE TABLE IF NOT EXISTS attempts (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  branch text NOT NULL CHECK (branch IN ('tangdaowan', 'east', 'qiushi')),
  status text NOT NULL CHECK (
    status IN ('unknown', 'submitted', 'accepted', 'confirmed', 'rejected', 'challenge', 'elapsed')
  ),
  prepared_enc bytea,
  prior_enc bytea,
  record_id_enc bytea,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS attempts_user_branch_created
  ON attempts (user_id, branch, created_at DESC);
CREATE INDEX IF NOT EXISTS attempts_user_branch_status
  ON attempts (user_id, branch, status);

CREATE TABLE IF NOT EXISTS cancellations (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  branch text NOT NULL CHECK (branch IN ('tangdaowan', 'east', 'qiushi')),
  reservation_key_hmac bytea NOT NULL,
  status text NOT NULL CHECK (
    status IN ('unknown', 'submitted', 'cancelled', 'absent', 'rejected')
  ),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS one_pending_cancellation
  ON cancellations (user_id, branch, reservation_key_hmac)
  WHERE status IN ('submitted', 'unknown');
CREATE INDEX IF NOT EXISTS cancellations_user_branch_status
  ON cancellations (user_id, branch, status);

CREATE TABLE IF NOT EXISTS audit (
  id bigserial PRIMARY KEY,
  user_id uuid REFERENCES users(id) ON DELETE SET NULL,
  action text NOT NULL,
  ip_hash bytea,
  ua_hash bytea,
  meta_enc bytea,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS audit_created ON audit (created_at);
