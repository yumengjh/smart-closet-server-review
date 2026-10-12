-- Issue #42: several named models (baselines), OOTD poses and body adjustments of a finished OOTD.
-- A model's name is a label only: renaming does not change its revision, so prepared OOTD stay valid.
ALTER TABLE person_baseline ADD COLUMN name VARCHAR(40);
-- Display-only facts of a render (pose label, adjustment values, model name); never a model input. Cleared on delete.
ALTER TABLE image_render_job ADD COLUMN summary_json TEXT;
-- Body adjustment: the finished OOTD job it starts from. The parent may be deleted later; the result stays.
ALTER TABLE image_render_job ADD COLUMN parent_job_id VARCHAR(36);

-- A user's pose cards. Only the extracted description and joint positions are kept, never the source picture.
CREATE TABLE pose_card (
    id VARCHAR(36) PRIMARY KEY,
    owner_username VARCHAR(160) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    name VARCHAR(40) NOT NULL,
    source VARCHAR(10) NOT NULL,
    description VARCHAR(600) NOT NULL,
    tags_json TEXT NOT NULL DEFAULT '[]',
    keypoints_json TEXT,
    view_angle VARCHAR(20),
    created_at_ms BIGINT NOT NULL
);
CREATE INDEX pose_card_owner ON pose_card(owner_username, created_at_ms);
-- One row per extraction attempt (success or not) for the daily limit; holds no picture or result.
CREATE TABLE pose_extraction_attempt (
    owner_username VARCHAR(160) NOT NULL REFERENCES closet_user(username) ON DELETE CASCADE,
    created_at_ms BIGINT NOT NULL
);
CREATE INDEX pose_attempt_owner ON pose_extraction_attempt(owner_username, created_at_ms);
