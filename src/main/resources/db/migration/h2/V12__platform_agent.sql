ALTER TABLE garment ADD COLUMN name VARCHAR(100);
ALTER TABLE garment ADD COLUMN favorite BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX garment_owner_status ON garment(owner_username,closet_status);
ALTER TABLE agent_session ADD COLUMN context_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_message ADD COLUMN context_epoch BIGINT NOT NULL DEFAULT 0;
CREATE TABLE agent_memory_document (
 owner_username VARCHAR(100) NOT NULL, kind VARCHAR(20) NOT NULL, content CLOB NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL, PRIMARY KEY(owner_username,kind));
CREATE TABLE agent_memory_use_grant (
 owner_username VARCHAR(100) NOT NULL, purpose VARCHAR(30) NOT NULL, document_revision BIGINT NOT NULL,
 expires_at BIGINT NOT NULL, PRIMARY KEY(owner_username,purpose));
CREATE TABLE agent_command (
 id VARCHAR(36) PRIMARY KEY, owner_username VARCHAR(100) NOT NULL, kind VARCHAR(50) NOT NULL,
 parameters_json CLOB NOT NULL, before_json CLOB, expected_revision BIGINT, after_revision BIGINT,
 status VARCHAR(30) NOT NULL, requires_confirmation BOOLEAN NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, result_json CLOB,
 UNIQUE(owner_username,idempotency_key));
