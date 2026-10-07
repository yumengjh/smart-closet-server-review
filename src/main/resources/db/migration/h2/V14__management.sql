CREATE TABLE closet_user_quota (
    owner_username VARCHAR(80) PRIMARY KEY REFERENCES closet_user(username) ON DELETE CASCADE,
    image_daily_limit INTEGER,
    agent_daily_limit INTEGER,
    updated_at_ms BIGINT NOT NULL,
    CHECK (image_daily_limit IS NULL OR image_daily_limit BETWEEN 0 AND 1000),
    CHECK (agent_daily_limit IS NULL OR agent_daily_limit BETWEEN 0 AND 1000)
);

CREATE TABLE management_operation_log (
    id VARCHAR(36) PRIMARY KEY,
    occurred_at_ms BIGINT NOT NULL,
    key_id VARCHAR(64) NOT NULL,
    operator VARCHAR(128),
    request_id VARCHAR(128),
    action VARCHAR(64) NOT NULL,
    target_username VARCHAR(80),
    result VARCHAR(16) NOT NULL,
    detail VARCHAR(1000)
);
CREATE INDEX idx_management_log_time ON management_operation_log(occurred_at_ms);
CREATE INDEX idx_closet_user_created ON closet_user(created_at_ms);
