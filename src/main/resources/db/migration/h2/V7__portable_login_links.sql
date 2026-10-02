CREATE TABLE portable_login_link (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    consumed_at_ms BIGINT,
    revoked_at_ms BIGINT
);

CREATE INDEX portable_login_owner_created ON portable_login_link(owner_username, created_at_ms);
