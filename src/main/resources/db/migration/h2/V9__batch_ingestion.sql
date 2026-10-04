CREATE TABLE clothing_batch (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(100) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    state_json TEXT NOT NULL
);
CREATE INDEX idx_batch_owner ON clothing_batch(owner_username, created_at_ms);
CREATE TABLE batch_call (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL REFERENCES clothing_batch(id),
    fingerprint VARCHAR(64) NOT NULL,
    type VARCHAR(20) NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload_json TEXT NOT NULL,
    result_json TEXT,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_batch_call_queue ON batch_call(status, created_at_ms);
