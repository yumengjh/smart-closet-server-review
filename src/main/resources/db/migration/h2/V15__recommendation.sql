-- Recommendation engine (docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md)

-- Every outfit actually shown, with the per-dimension scores at that time (5.17: future weight learning).
CREATE TABLE recommendation_log (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    source VARCHAR(20) NOT NULL,
    intent_json CLOB NOT NULL,
    outfit_key VARCHAR(36) NOT NULL,
    garment_ids_json CLOB NOT NULL,
    rank_no INTEGER NOT NULL,
    total_score DOUBLE PRECISION NOT NULL,
    score_vector_json CLOB NOT NULL,
    tags_json CLOB NOT NULL,
    rule_version VARCHAR(40) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    UNIQUE (run_id, outfit_key)
);
CREATE INDEX idx_recommendation_log_owner ON recommendation_log(owner_username, created_at_ms DESC);

CREATE TABLE recommendation_feedback (
    id VARCHAR(36) PRIMARY KEY,
    log_id VARCHAR(36) NOT NULL REFERENCES recommendation_log(id) ON DELETE CASCADE,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    verdict VARCHAR(8) NOT NULL,
    reasons_json CLOB NOT NULL,
    garment_id VARCHAR(36),
    created_at_ms BIGINT NOT NULL,
    CHECK (verdict IN ('UP', 'DOWN'))
);
CREATE INDEX idx_recommendation_feedback_owner ON recommendation_feedback(owner_username, created_at_ms DESC);

-- Weak behaviour signals (D19): opened in workspace, swapped out, detail viewed.
CREATE TABLE style_signal (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    kind VARCHAR(30) NOT NULL,
    log_id VARCHAR(36),
    garment_id VARCHAR(36),
    created_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_style_signal_owner ON style_signal(owner_username, created_at_ms DESC);

-- "今天穿了这套" (D6).
CREATE TABLE wear_log (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    outfit_id VARCHAR(36),
    garment_ids_json CLOB NOT NULL,
    worn_on DATE NOT NULL,
    created_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_wear_log_owner ON wear_log(owner_username, worn_on DESC);

-- Per-user recommendation switches (D5, D9, D20, D24) and blocked profile statements.
CREATE TABLE recommend_setting (
    owner_username VARCHAR(80) PRIMARY KEY REFERENCES closet_user(username) ON DELETE CASCADE,
    boldness VARCHAR(20) NOT NULL DEFAULT 'BALANCED',
    exploration BOOLEAN NOT NULL DEFAULT TRUE,
    profile_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    city VARCHAR(80),
    blocked_json CLOB NOT NULL DEFAULT '[]',
    profile_reset_at_ms BIGINT NOT NULL DEFAULT 0,
    updated_at_ms BIGINT NOT NULL,
    CHECK (boldness IN ('CONSERVATIVE', 'BALANCED', 'BOLD'))
);
