CREATE TABLE account (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(320) NOT NULL,
    email         VARCHAR(320),
    full_name     VARCHAR(200) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    failed_logins INTEGER      NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT account_role_check CHECK (role IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT account_failed_logins_not_negative CHECK (failed_logins >= 0)
);

CREATE UNIQUE INDEX account_username_ci_uq ON account (lower(username));

ALTER TABLE customer_order ADD COLUMN account_id BIGINT REFERENCES account (id);

CREATE INDEX customer_order_account_idx ON customer_order (account_id, created_at DESC);
