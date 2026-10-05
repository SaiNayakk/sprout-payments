-- AutoPay: a customer's mandate with their bank, the debits Sprout makes under it, and the UPI spends
-- the bank shares while it is active (what round-ups are made from).

CREATE TABLE mandates (
    id               uuid PRIMARY KEY,           -- also the mandate's reference at the bank
    user_id          uuid NOT NULL,
    idempotency_key  text NOT NULL,
    max_amount_paise bigint NOT NULL CHECK (max_amount_paise > 0),
    bank_mandate_id  uuid,
    status           text NOT NULL CHECK (status IN ('AWAITING_APPROVAL', 'ACTIVE', 'DECLINED', 'EXPIRED', 'REVOKED', 'FAILED')),
    failure_reason   text,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    UNIQUE (user_id, idempotency_key)
);
CREATE INDEX mandates_by_user ON mandates (user_id, created_at DESC);

-- Each debit's reference is the caller's (unique), so a retried debit finds the first one.
CREATE TABLE mandate_debits (
    id             uuid PRIMARY KEY,             -- also the debit's reference at the bank
    user_id        uuid NOT NULL,
    mandate_id     uuid REFERENCES mandates (id),
    reference      text NOT NULL UNIQUE,
    amount_paise   bigint NOT NULL CHECK (amount_paise > 0),
    description    text NOT NULL,
    status         text NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    failure_reason text,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL
);
CREATE INDEX mandate_debits_pending ON mandate_debits (updated_at) WHERE status = 'PENDING';

-- Spends in the order they arrived: readers page through them by seq and see each once.
CREATE TABLE spends (
    seq          bigserial PRIMARY KEY,
    id           uuid NOT NULL UNIQUE,           -- the bank's id for the payment
    user_id      uuid NOT NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    payee_name   text NOT NULL,
    at           timestamptz NOT NULL
);
