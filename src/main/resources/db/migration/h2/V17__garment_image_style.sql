-- How a garment's display image is drawn (写实照片 / 卡通插画 / 简笔线稿 / 其他). It belongs to the display image, so a
-- new display image (re-enhanced, cartoonised) is classified again. source: MODEL (one batched vision call) or USER.
CREATE TABLE garment_image_style (
    garment_id VARCHAR(36) PRIMARY KEY REFERENCES garment(id) ON DELETE CASCADE,
    owner_username VARCHAR(160) NOT NULL,
    display_asset_id VARCHAR(36) NOT NULL,
    style VARCHAR(20) NOT NULL,
    source VARCHAR(10) NOT NULL,
    evidence VARCHAR(300),
    updated_at_ms BIGINT NOT NULL,
    CHECK (style IN ('PHOTO', 'ILLUSTRATION', 'SKETCH', 'OTHER')),
    CHECK (source IN ('MODEL', 'USER'))
);
CREATE INDEX idx_garment_image_style_owner ON garment_image_style(owner_username);

-- Whether one outfit may mix image styles: ALLOW, PENALTY (default, ranked lower with a reason) or FORBID.
ALTER TABLE recommend_setting ADD COLUMN style_mixing VARCHAR(16) DEFAULT 'PENALTY' NOT NULL;
