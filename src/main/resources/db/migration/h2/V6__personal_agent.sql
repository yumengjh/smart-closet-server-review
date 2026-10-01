CREATE TABLE agent_session (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(100) NOT NULL,
 title VARCHAR(100) NOT NULL, model VARCHAR(160), active_run VARCHAR(36), active_at BIGINT,
 created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX agent_session_owner ON agent_session(owner_username, updated_at);
CREATE TABLE agent_message (
 id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
 role VARCHAR(16) NOT NULL, content CLOB NOT NULL, status VARCHAR(16) NOT NULL,
 events_json CLOB NOT NULL, created_at BIGINT NOT NULL
);
CREATE INDEX agent_message_session ON agent_message(session_id, created_at);
CREATE TABLE agent_preference (owner_username VARCHAR(100) PRIMARY KEY, model VARCHAR(160) NOT NULL);
CREATE TABLE agent_memory (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(100) NOT NULL, content VARCHAR(500) NOT NULL,
 category VARCHAR(24) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL
);
CREATE INDEX agent_memory_owner ON agent_memory(owner_username);
CREATE TABLE agent_daily_quota (
 owner_username VARCHAR(100) NOT NULL, quota_date DATE NOT NULL, used INTEGER NOT NULL,
 PRIMARY KEY(owner_username, quota_date)
);
CREATE TABLE agent_proposal (
 id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
 owner_username VARCHAR(100) NOT NULL, kind VARCHAR(16) NOT NULL, payload_json CLOB NOT NULL,
 context_json CLOB NOT NULL, consumed BOOLEAN NOT NULL DEFAULT FALSE, created_at BIGINT NOT NULL
);
