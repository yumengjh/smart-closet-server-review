-- Image vectors, duplicate notifications and the one-click scan (docs/design/2026-10-09-模型内存管理与图像向量设计.md, G10~G17).

-- Local image-text model vector of the display image (cut-out composited on white). Bound to the display asset and the
-- image tower version, so a new display image or a different model file is re-encoded in the background.
CREATE TABLE garment_image_embedding (
    garment_id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    display_asset_id VARCHAR(36) NOT NULL,
    model_version VARCHAR(120) NOT NULL,
    vector BLOB,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_image_embedding_owner ON garment_image_embedding(owner_username);

-- In-site notifications (top-right processing centre). dedupe_key keeps one notification per event (e.g. per pair).
CREATE TABLE user_notification (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    href VARCHAR(300),
    payload_json CLOB,
    dedupe_key VARCHAR(200),
    created_at_ms BIGINT NOT NULL,
    read_at_ms BIGINT
);
CREATE INDEX idx_notification_owner ON user_notification(owner_username, created_at_ms);
CREATE UNIQUE INDEX uq_notification_dedupe ON user_notification(owner_username, dedupe_key);

-- One duplicate check per garment when it enters the closet (or changes its display image).
CREATE TABLE garment_duplicate_check (
    garment_id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    display_asset_id VARCHAR(36),
    found INT NOT NULL,
    checked_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_duplicate_check_owner ON garment_duplicate_check(owner_username);
-- Garments already in the closet count as checked: going live must not flood anyone with old duplicates (use the scan).
INSERT INTO garment_duplicate_check(garment_id, owner_username, display_asset_id, found, checked_at_ms)
SELECT id, owner_username, display_asset_id, 0, 0 FROM garment WHERE closet_status = 'ACTIVE' AND owner_username IS NOT NULL;

-- Pairs already reported as possible duplicates, so each pair notifies once (also when it was part of a summary).
CREATE TABLE duplicate_notified_pair (
    owner_username VARCHAR(80) NOT NULL,
    garment_a VARCHAR(36) NOT NULL,
    garment_b VARCHAR(36) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (garment_a, garment_b)
);

-- Manual "scan my closet for duplicates" runs; at most one active run per user.
CREATE TABLE duplicate_scan (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(80) NOT NULL,
    status VARCHAR(20) NOT NULL,
    pairs INT,
    new_pairs INT,
    image_state VARCHAR(30),
    requested_at_ms BIGINT NOT NULL,
    finished_at_ms BIGINT,
    error VARCHAR(300)
);
CREATE INDEX idx_duplicate_scan_owner ON duplicate_scan(owner_username, requested_at_ms);
