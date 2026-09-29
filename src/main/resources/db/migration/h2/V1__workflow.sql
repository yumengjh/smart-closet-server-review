CREATE TABLE workflow_job (
    id VARCHAR(36) PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    request_json TEXT NOT NULL,
    input_url VARCHAR(255),
    output_url VARCHAR(255),
    requires_review INTEGER NOT NULL,
    error_json TEXT,
    parent_job_id VARCHAR(36),
    selected_asset_id VARCHAR(36),
    version BIGINT NOT NULL
);
CREATE INDEX idx_workflow_queue ON workflow_job(status, created_at_ms, id);
CREATE INDEX idx_workflow_history ON workflow_job(created_at_ms, id);
CREATE TABLE workflow_step (
    id VARCHAR(48) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES workflow_job(id),
    step_no INTEGER NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    result_json TEXT NOT NULL,
    UNIQUE(job_id, step_no)
);
CREATE TABLE generation_attempt (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES workflow_job(id),
    step_no INTEGER NOT NULL,
    attempt_no INTEGER NOT NULL,
    provider_id VARCHAR(160) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    provider_request_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    request_json TEXT NOT NULL,
    started_at_ms BIGINT NOT NULL,
    finished_at_ms BIGINT,
    usage_json TEXT,
    error_code VARCHAR(100),
    UNIQUE(job_id, step_no, attempt_no)
);
CREATE INDEX idx_attempt_job ON generation_attempt(job_id, step_no);
CREATE TABLE image_asset (
    id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES workflow_job(id),
    file_name VARCHAR(40) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    kind VARCHAR(32) NOT NULL,
    review_status VARCHAR(32) NOT NULL,
    review_note VARCHAR(1000),
    reviewed_at_ms BIGINT,
    created_at_ms BIGINT NOT NULL,
    UNIQUE(job_id, file_name)
);
