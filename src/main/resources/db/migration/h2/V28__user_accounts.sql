-- User accounts for launch: self registration, profile, account events (design docs/design/2026-10-19-用户账号：注册、资料与管理设计.md).
ALTER TABLE closet_user ADD COLUMN display_name VARCHAR(40);
ALTER TABLE closet_user ADD COLUMN last_login_at_ms BIGINT;
ALTER TABLE closet_user ADD COLUMN registered_via VARCHAR(16) DEFAULT 'ADMIN' NOT NULL;
ALTER TABLE closet_user ADD COLUMN invite_id VARCHAR(36);
ALTER TABLE closet_user ADD COLUMN deactivated_at_ms BIGINT;
ALTER TABLE closet_user ADD CONSTRAINT closet_user_registered_via CHECK (registered_via IN ('ADMIN', 'INVITE', 'OPEN'));

CREATE TABLE registration_invite (
    id VARCHAR(36) PRIMARY KEY,
    code_hash CHAR(64) NOT NULL UNIQUE,
    note VARCHAR(100),
    max_uses INTEGER NOT NULL,
    used_count INTEGER NOT NULL DEFAULT 0,
    created_by VARCHAR(200) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    revoked_at_ms BIGINT,
    CHECK (max_uses BETWEEN 1 AND 100),
    CHECK (used_count BETWEEN 0 AND max_uses)
);
CREATE INDEX idx_registration_invite_created ON registration_invite(created_at_ms);

CREATE TABLE account_event (
    id VARCHAR(36) PRIMARY KEY,
    occurred_at_ms BIGINT NOT NULL,
    username VARCHAR(80) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    action VARCHAR(48) NOT NULL,
    detail VARCHAR(500)
);
CREATE INDEX idx_account_event_user_time ON account_event(username, occurred_at_ms);
CREATE INDEX idx_account_event_time ON account_event(occurred_at_ms);
