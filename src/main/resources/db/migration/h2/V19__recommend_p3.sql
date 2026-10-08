-- Smart outfit P3 (docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md, D28–D31).

-- Image-style mixing learns from feedback by default (D29). PENALTY was the pre-P3 default and means the same.
UPDATE recommend_setting SET style_mixing='AUTO' WHERE style_mixing='PENALTY';
ALTER TABLE recommend_setting ALTER COLUMN style_mixing SET DEFAULT 'AUTO';

-- Semantic search strictness 0–100 per account (D30); NULL means the recommended level.
ALTER TABLE recommend_setting ADD COLUMN semantic_strictness INTEGER;

-- Admin review of curated outfit templates (D31). Overrides the defaults in resources/recommend/outfit-templates.json
-- without rewriting that file; a missing row means "use the file".
CREATE TABLE outfit_template_review (
    template_id VARCHAR(80) PRIMARY KEY,
    review_status VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    bonus DOUBLE PRECISION,
    updated_by VARCHAR(80) NOT NULL,
    updated_at_ms BIGINT NOT NULL,
    CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
);

-- One-off backfill of image styles for garments recognised before the recognition schema carried imageStyle (D29).
CREATE TABLE app_marker (
    name VARCHAR(80) PRIMARY KEY,
    value_ms BIGINT NOT NULL
);
