-- Runtime overrides set by administrators (management API). A missing row means the deployment configuration applies.
CREATE TABLE closet_setting (
    setting_key VARCHAR(64) PRIMARY KEY,
    setting_value VARCHAR(200) NOT NULL,
    updated_by VARCHAR(200) NOT NULL,
    updated_at_ms BIGINT NOT NULL
);
