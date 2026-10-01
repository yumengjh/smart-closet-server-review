CREATE TABLE outfit (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username),
    name VARCHAR(100) NOT NULL,
    scene_version INTEGER NOT NULL,
    scene_json CLOB NOT NULL,
    revision BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);

CREATE INDEX idx_outfit_owner_updated ON outfit(owner_username, updated_at_ms DESC, id);
