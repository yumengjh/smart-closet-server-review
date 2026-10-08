-- Style words learned from chat: when the assistant model classifies a word the dictionary does not know
-- ("多巴胺" → 甜美/休闲) in the call it makes anyway, the mapping is kept so pages and search can use it without a model.
CREATE TABLE style_term (
    term VARCHAR(40) PRIMARY KEY,
    styles VARCHAR(80) NOT NULL,
    source VARCHAR(16) NOT NULL,
    hits INTEGER NOT NULL DEFAULT 1,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at_ms BIGINT NOT NULL,
    CHECK (source IN ('ASSISTANT', 'ADMIN'))
);
