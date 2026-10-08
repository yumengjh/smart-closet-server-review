-- Local semantic index (docs/design/2026-10-08-智能搭配推荐引擎与语义召回设计.md 5.9).
-- One vector per garment and model version. card_hash skips re-encoding when only non-descriptive fields changed;
-- garment_revision lets the background scan find stale rows with a plain comparison instead of rebuilding cards.
CREATE TABLE garment_embedding (
    garment_id VARCHAR(36) PRIMARY KEY REFERENCES garment(id) ON DELETE CASCADE,
    owner_username VARCHAR(160) NOT NULL,
    model_version VARCHAR(80) NOT NULL,
    garment_revision BIGINT NOT NULL,
    card_hash VARCHAR(64) NOT NULL,
    card_text VARCHAR(2000) NOT NULL,
    vector BLOB NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_garment_embedding_owner ON garment_embedding(owner_username, model_version);
