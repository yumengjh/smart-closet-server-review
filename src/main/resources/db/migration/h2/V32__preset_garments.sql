-- System owner is an image namespace, never an account (including case variants).
ALTER TABLE closet_user ADD CONSTRAINT reserved_preset_owner CHECK (LOWER(username) <> '__preset__');
CREATE TABLE preset_image (
    sha256 VARCHAR(64) PRIMARY KEY,
    asset_id VARCHAR(36) NOT NULL UNIQUE REFERENCES image_asset(id),
    image_style VARCHAR(20)
);
CREATE TABLE preset_garment (
    id VARCHAR(36) PRIMARY KEY,
    source_garment_id VARCHAR(36) NOT NULL UNIQUE,
    source_owner VARCHAR(80) NOT NULL,
    display_asset_id VARCHAR(36) NOT NULL REFERENCES image_asset(id),
    name VARCHAR(100),
    fields_json TEXT NOT NULL,
    gender VARCHAR(20) NOT NULL,
    styles_json TEXT NOT NULL,
    seasons_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','LISTED','UNLISTED')),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
CREATE INDEX idx_preset_status_sort ON preset_garment(status,sort_order,id);
ALTER TABLE garment ADD COLUMN preset_id VARCHAR(36) REFERENCES preset_garment(id);
CREATE UNIQUE INDEX uq_garment_owner_preset ON garment(owner_username,preset_id);
-- Serialises library imports, including two application instances sharing the database.
INSERT INTO app_marker(name,value_ms) VALUES('preset-import-lock',0);
