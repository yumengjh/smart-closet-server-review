-- Similar garments and search by image (docs/design/2026-10-09-相似衣物提示与以图搜图设计.md, S1~S8).

-- Pure-Java image fingerprint of the display image (perceptual hash, 8x8 silhouette grid, aspect, palette).
-- Bound to the display asset like garment_color; drafts are included so duplicates can be flagged before adoption.
CREATE TABLE garment_visual (
    garment_id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    display_asset_id VARCHAR(36) NOT NULL,
    algorithm VARCHAR(40) NOT NULL,
    phash BIGINT NOT NULL,
    mask_grid BIGINT NOT NULL,
    aspect DOUBLE PRECISION NOT NULL,
    palette_json CLOB NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_garment_visual_owner ON garment_visual(owner_username);

-- "Not a duplicate": an unordered pair the user has dismissed (garment_a < garment_b).
CREATE TABLE garment_similar_dismissal (
    owner_username VARCHAR(80) NOT NULL,
    garment_a VARCHAR(36) NOT NULL,
    garment_b VARCHAR(36) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (garment_a, garment_b)
);
CREATE INDEX idx_similar_dismissal_owner ON garment_similar_dismissal(owner_username);
