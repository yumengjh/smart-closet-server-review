ALTER TABLE workflow_job ADD COLUMN processing_kind VARCHAR(32) NOT NULL DEFAULT 'LEGACY';
CREATE TABLE processing_config (id VARCHAR(160) PRIMARY KEY, value_json TEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 0, updated_by VARCHAR(160), updated_at_ms BIGINT NOT NULL);
CREATE TABLE execution_task (
 id VARCHAR(36) PRIMARY KEY, business_key VARCHAR(180) NOT NULL UNIQUE, owner_username VARCHAR(160), kind VARCHAR(40) NOT NULL,
 business_id VARCHAR(36) NOT NULL, stage INTEGER NOT NULL, pool VARCHAR(24) NOT NULL, account_id VARCHAR(160), priority INTEGER NOT NULL,
 status VARCHAR(32) NOT NULL, next_run_at_ms BIGINT NOT NULL, claim_token VARCHAR(36), version BIGINT NOT NULL DEFAULT 0,
 created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL, wait_reason VARCHAR(160), error_message VARCHAR(1000), payload_json TEXT
);
CREATE INDEX idx_execution_due ON execution_task(status,pool,next_run_at_ms,priority,created_at_ms);
CREATE INDEX idx_execution_owner ON execution_task(owner_username,created_at_ms);
CREATE TABLE execution_attempt (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL REFERENCES execution_task(id), attempt_no INTEGER NOT NULL,
 status VARCHAR(32) NOT NULL, dispatched INTEGER NOT NULL DEFAULT 0, quota_date DATE, quota_units INTEGER NOT NULL DEFAULT 0,
 started_at_ms BIGINT NOT NULL, finished_at_ms BIGINT, usage_json TEXT, error_message VARCHAR(1000), UNIQUE(task_id,attempt_no)
);
CREATE TABLE resource_permit (
 token VARCHAR(36) PRIMARY KEY, account_id VARCHAR(160) NOT NULL, purpose VARCHAR(24) NOT NULL,
 binding VARCHAR(180) NOT NULL UNIQUE, status VARCHAR(24) NOT NULL, deadline_ms BIGINT NOT NULL,
 heartbeat_ms BIGINT NOT NULL, dispatched_at_ms BIGINT, created_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_permit_account ON resource_permit(account_id,status,deadline_ms);
CREATE TABLE quick_intake (
 id VARCHAR(36) PRIMARY KEY REFERENCES workflow_job(id), owner_username VARCHAR(160) NOT NULL, capture_session_id VARCHAR(36),
 admission_mode VARCHAR(24) NOT NULL, fingerprint VARCHAR(64) NOT NULL, hint_json TEXT, await_hint INTEGER NOT NULL DEFAULT 0,
 quality_json TEXT, generation_json TEXT, generation_revision VARCHAR(64), base_asset_id VARCHAR(36),
 parent_intake_id VARCHAR(36), feedback VARCHAR(1000), revision BIGINT NOT NULL DEFAULT 0, created_at_ms BIGINT NOT NULL
);

-- Historical reservations cannot be reconstructed without their original day; retain their accounting.
ALTER TABLE workflow_job ADD COLUMN legacy_prepaid INTEGER NOT NULL DEFAULT 1;
ALTER TABLE workflow_job ALTER COLUMN legacy_prepaid SET DEFAULT 0;
ALTER TABLE batch_call ADD COLUMN legacy_prepaid INTEGER NOT NULL DEFAULT 1;
ALTER TABLE batch_call ALTER COLUMN legacy_prepaid SET DEFAULT 0;

ALTER TABLE execution_task ADD COLUMN dependency_key VARCHAR(180);
ALTER TABLE execution_task ADD COLUMN input_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE execution_task ADD COLUMN result_json TEXT;
