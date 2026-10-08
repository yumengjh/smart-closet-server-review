-- Smart outfit P4 (docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md, D34–D39).

-- 今日灵感 remembers which template produced each shown outfit, so the daily rotation avoids repeating a template.
ALTER TABLE recommendation_log ADD COLUMN template_id VARCHAR(80);
CREATE INDEX idx_recommendation_log_source ON recommendation_log(owner_username, source, created_at_ms DESC);

-- Precise colours (local k-means on the display image, CIELAB). Recomputed when the display image changes.
CREATE TABLE garment_color (
    garment_id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    display_asset_id VARCHAR(36) NOT NULL,
    palette_json CLOB NOT NULL,
    algorithm VARCHAR(40) NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_garment_color_owner ON garment_color(owner_username);

-- Model usage per feature (cost statistics). One row per finished model-backed request; counts only, no content.
CREATE TABLE model_usage (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    feature VARCHAR(30) NOT NULL,
    model VARCHAR(160),
    input_tokens BIGINT,
    output_tokens BIGINT,
    created_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_model_usage_owner ON model_usage(owner_username, created_at_ms DESC);
