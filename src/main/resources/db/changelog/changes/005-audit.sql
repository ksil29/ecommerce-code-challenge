CREATE TABLE audit_event (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor       VARCHAR(320) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   VARCHAR(320),
    details     JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX audit_event_occurred_idx ON audit_event (occurred_at DESC, id DESC);
CREATE INDEX audit_event_entity_idx ON audit_event (entity_type, entity_id);
