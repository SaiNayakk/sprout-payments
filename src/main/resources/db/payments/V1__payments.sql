-- Payments keeps the story of each payment; the ledger keeps the money. A retried request with the
-- same Idempotency-Key finds its payment here instead of making a second one.

CREATE TABLE deposits (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL,
    idempotency_key text NOT NULL,
    amount_paise    bigint NOT NULL CHECK (amount_paise > 0),
    status          text NOT NULL CHECK (status IN ('AWAITING_APPROVAL', 'COMPLETED', 'DECLINED', 'EXPIRED', 'FAILED')),
    bank_request_id uuid,
    failure_reason  text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX deposits_by_user ON deposits (user_id, created_at DESC);
CREATE INDEX deposits_waiting ON deposits (updated_at) WHERE status = 'AWAITING_APPROVAL';

CREATE TABLE withdrawals (
    id              uuid PRIMARY KEY,
    user_id         uuid NOT NULL,
    idempotency_key text NOT NULL,
    amount_paise    bigint NOT NULL CHECK (amount_paise > 0),
    payee_vpa       text NOT NULL,
    status          text NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED')),
    failure_reason  text,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX withdrawals_by_user ON withdrawals (user_id, created_at DESC);
CREATE INDEX withdrawals_processing ON withdrawals (updated_at) WHERE status = 'PROCESSING';

-- Bank callbacks already applied (they can arrive more than once).
CREATE TABLE bank_events (
    event_id    uuid PRIMARY KEY,
    received_at timestamptz NOT NULL
);
