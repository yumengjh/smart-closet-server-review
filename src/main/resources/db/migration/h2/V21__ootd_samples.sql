-- Full-body enhancement (docs/design/2026-10-09-全身穿搭与呈现增强设计.md, D47~D49).

-- Whole-outfit sample photos collected by admins. The photo itself is kept only until review (image_path), never in
-- the database; extraction_json/reviewed_json hold the structured ootd-v1 result without identifying details.
CREATE TABLE ootd_sample (
    id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(20) NOT NULL,
    source_url VARCHAR(1000),
    license VARCHAR(20) NOT NULL,
    note VARCHAR(500),
    image_sha256 VARCHAR(64) NOT NULL,
    image_path VARCHAR(500),
    extractor_version VARCHAR(80),
    model VARCHAR(160),
    extraction_json CLOB,
    reviewed_json CLOB,
    error_message VARCHAR(500),
    reject_reason VARCHAR(40),
    presentation VARCHAR(20),
    created_by VARCHAR(80) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    reviewed_by VARCHAR(80),
    reviewed_at_ms BIGINT,
    template_id VARCHAR(80)
);
CREATE UNIQUE INDEX uq_ootd_sample_sha ON ootd_sample(image_sha256);
CREATE INDEX idx_ootd_sample_status ON ootd_sample(status, created_at_ms DESC);

-- Template drafts generated from approved samples; reviewed on /admin/templates like the bundled ones.
CREATE TABLE outfit_template_custom (
    template_id VARCHAR(80) PRIMARY KEY,
    definition_json CLOB NOT NULL,
    source_sample_id VARCHAR(36),
    created_by VARCHAR(80) NOT NULL,
    created_at_ms BIGINT NOT NULL
);
