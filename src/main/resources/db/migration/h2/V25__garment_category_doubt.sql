-- Issue #5: the user said a recommended garment's category is wrong (e.g. a skirt recognised as a dress and shown alone).
-- While the garment stays at the revision it had then, it is kept out of automatic recommendations; editing or checking
-- the garment (any new revision) lifts it.
CREATE TABLE garment_category_doubt (
    owner_username VARCHAR(80) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    garment_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    reason VARCHAR(20) NOT NULL,
    created_at_ms BIGINT NOT NULL,
    PRIMARY KEY (owner_username, garment_id)
);
