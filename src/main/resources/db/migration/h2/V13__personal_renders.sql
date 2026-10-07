CREATE TABLE wearer_profile (
 owner_username VARCHAR(160) PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0, sex VARCHAR(10),
 height_cm DOUBLE PRECISION, weight_kg DOUBLE PRECISION, body_shape VARCHAR(500), measurements_json TEXT NOT NULL DEFAULT '{}',
 preferences_json TEXT NOT NULL DEFAULT '{}', default_baseline_id VARCHAR(36), updated_at_ms BIGINT NOT NULL);
CREATE TABLE private_media (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(160) NOT NULL, purpose VARCHAR(30) NOT NULL,
 sha256 VARCHAR(64) NOT NULL, state VARCHAR(20) NOT NULL, deletion_epoch BIGINT NOT NULL DEFAULT 0,
 created_at_ms BIGINT NOT NULL, source_job_id VARCHAR(36), source_job_epoch BIGINT);
CREATE TABLE person_baseline (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(160) NOT NULL, revision BIGINT NOT NULL DEFAULT 0,
 media_id VARCHAR(36), source_photo_id VARCHAR(36), profile_snapshot_json TEXT NOT NULL,
 status VARCHAR(30) NOT NULL, created_at_ms BIGINT NOT NULL);
CREATE TABLE outfit_plan (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(160) NOT NULL, outfit_id VARCHAR(36) NOT NULL,
 outfit_revision BIGINT NOT NULL, baseline_id VARCHAR(36) NOT NULL, baseline_revision BIGINT NOT NULL,
 review_json TEXT NOT NULL, profile_snapshot_json TEXT NOT NULL, input_json TEXT NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, expires_at_ms BIGINT NOT NULL, created_at_ms BIGINT NOT NULL);
CREATE TABLE image_render_job (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(160) NOT NULL, purpose VARCHAR(20) NOT NULL,
 baseline_id VARCHAR(36), plan_id VARCHAR(36), input_json TEXT NOT NULL, status VARCHAR(30) NOT NULL,
 media_id VARCHAR(36), error_message VARCHAR(1000), feedback VARCHAR(1000), deletion_epoch BIGINT NOT NULL DEFAULT 0,
 idempotency_key VARCHAR(100) NOT NULL, created_at_ms BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
 UNIQUE(owner_username,idempotency_key));
CREATE TABLE render_model_capability (
 provider VARCHAR(100) NOT NULL, model VARCHAR(160) NOT NULL, purpose VARCHAR(20) NOT NULL,
 max_references INTEGER NOT NULL, verified BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 1,
 verified_at_ms BIGINT, provider_revision VARCHAR(64), notes VARCHAR(1000), output_options_json TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(provider,model,purpose));
CREATE INDEX render_owner_created ON image_render_job(owner_username,created_at_ms);
CREATE TABLE render_media_dependency (
 job_id VARCHAR(36) NOT NULL, media_id VARCHAR(36) NOT NULL, deletion_epoch BIGINT NOT NULL,
 PRIMARY KEY(job_id,media_id));

CREATE UNIQUE INDEX render_once_per_plan ON image_render_job(plan_id);
