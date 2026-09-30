CREATE TABLE garment (
    id VARCHAR(36) PRIMARY KEY,
    source_asset_id VARCHAR(36) NOT NULL REFERENCES image_asset(id),
    display_asset_id VARCHAR(36) NOT NULL REFERENCES image_asset(id),
    source_origin VARCHAR(32) NOT NULL,
    automatic_json TEXT,
    overrides_json TEXT NOT NULL,
    latest_recognition_job_id VARCHAR(36),
    applied_recognition_job_id VARCHAR(36),
    latest_image_job_id VARCHAR(36),
    display_user_selected INTEGER NOT NULL,
    revision BIGINT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
ALTER TABLE workflow_job ADD COLUMN garment_id VARCHAR(36) REFERENCES garment(id);
CREATE INDEX idx_workflow_garment ON workflow_job(garment_id, created_at_ms);
