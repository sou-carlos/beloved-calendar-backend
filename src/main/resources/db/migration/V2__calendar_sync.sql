CREATE TABLE calendar_records (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    record_id VARCHAR(100) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    document JSONB,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, record_id)
);
-- A null document is a permanent deletion marker, including for offline clients.
CREATE TABLE calendar_operations (
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    operation_id UUID NOT NULL,
    request JSONB NOT NULL,
    response JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, operation_id)
);
