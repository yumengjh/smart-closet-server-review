CREATE TABLE closet_user (
    username VARCHAR(80) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    must_change_password INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 1,
    created_at_ms BIGINT NOT NULL,
    CHECK (role IN ('ADMIN', 'USER'))
);

ALTER TABLE workflow_job ADD COLUMN owner_username VARCHAR(80);
ALTER TABLE garment ADD COLUMN owner_username VARCHAR(80);
ALTER TABLE garment ADD COLUMN closet_status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL;
ALTER TABLE garment ADD COLUMN restore_status VARCHAR(16);
ALTER TABLE garment ALTER COLUMN closet_status SET DEFAULT 'DRAFT';
UPDATE garment SET closet_status='DRAFT';

CREATE INDEX idx_workflow_owner_history ON workflow_job(owner_username, created_at_ms, id);
CREATE INDEX idx_garment_owner_status ON garment(owner_username, closet_status, updated_at_ms, id);

CREATE TABLE standalone_segmentation_owner (
    segmentation_id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    created_at_ms BIGINT NOT NULL
);

CREATE TABLE ai_daily_quota (
    owner_username VARCHAR(80) NOT NULL,
    quota_date DATE NOT NULL,
    used INTEGER NOT NULL,
    PRIMARY KEY(owner_username, quota_date)
);
