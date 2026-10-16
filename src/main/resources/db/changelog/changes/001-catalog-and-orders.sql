CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE category (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    CONSTRAINT category_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX category_name_ci_uq ON category (lower(name));

CREATE TABLE product (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku           VARCHAR(64)    NOT NULL,
    name          VARCHAR(200)   NOT NULL,
    description   VARCHAR(10000),
    category_id   BIGINT         NOT NULL REFERENCES category (id),
    price         NUMERIC(12, 2) NOT NULL,
    stock         INTEGER        NOT NULL,
    reserved      INTEGER        NOT NULL DEFAULT 0,
    weight_kg     NUMERIC(10, 3),
    archived      BOOLEAN        NOT NULL DEFAULT FALSE,
    version       BIGINT         NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        setweight(to_tsvector('english', coalesce(name, '')), 'A') ||
        setweight(to_tsvector('simple', sku), 'A') ||
        setweight(to_tsvector('english', coalesce(description, '')), 'B')
    ) STORED,
    CONSTRAINT product_sku_uq UNIQUE (sku),
    CONSTRAINT product_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT product_price_positive CHECK (price > 0),
    CONSTRAINT product_stock_not_negative CHECK (stock >= 0),
    CONSTRAINT product_reserved_not_negative CHECK (reserved >= 0),
    CONSTRAINT product_weight_not_negative CHECK (weight_kg >= 0)
);

CREATE INDEX product_search_idx ON product USING GIN (search_vector);
CREATE INDEX product_name_trgm_idx ON product USING GIN (name gin_trgm_ops);
CREATE INDEX product_sku_trgm_idx ON product USING GIN (sku gin_trgm_ops);
CREATE INDEX product_category_idx ON product (category_id);
CREATE INDEX product_price_idx ON product (price);

CREATE TABLE customer_order (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id         UUID           NOT NULL,
    idempotency_key   UUID,
    request_hash      CHAR(64),
    status            VARCHAR(20)    NOT NULL,
    customer_name     VARCHAR(200)   NOT NULL,
    customer_email    VARCHAR(320)   NOT NULL,
    total             NUMERIC(18, 2) NOT NULL,
    currency          CHAR(3)        NOT NULL,
    card_last4        CHAR(4),
    payment_reference VARCHAR(64),
    failure_reason    VARCHAR(200),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT customer_order_public_id_uq UNIQUE (public_id),
    CONSTRAINT customer_order_idempotency_key_uq UNIQUE (idempotency_key),
    CONSTRAINT customer_order_status_check CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED', 'EXPIRED')),
    CONSTRAINT customer_order_total_positive CHECK (total > 0)
);

CREATE INDEX customer_order_pending_idx ON customer_order (created_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX customer_order_created_idx ON customer_order (created_at DESC);

CREATE TABLE order_line (
    order_id     BIGINT         NOT NULL REFERENCES customer_order (id),
    line_no      INTEGER        NOT NULL,
    product_id   BIGINT         NOT NULL REFERENCES product (id),
    sku          VARCHAR(64)    NOT NULL,
    product_name VARCHAR(200)   NOT NULL,
    unit_price   NUMERIC(12, 2) NOT NULL,
    quantity     INTEGER        NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT order_line_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX order_line_product_idx ON order_line (product_id);
