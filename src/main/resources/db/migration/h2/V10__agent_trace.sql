ALTER TABLE agent_message ADD COLUMN request_id VARCHAR(36);
CREATE TABLE agent_trace_run (
 request_id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
 status VARCHAR(24) NOT NULL, next_seq BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL
);
CREATE TABLE agent_trace_event (
 request_id VARCHAR(36) NOT NULL REFERENCES agent_trace_run(request_id) ON DELETE CASCADE,
 seq BIGINT NOT NULL, phase VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL, label VARCHAR(200) NOT NULL,
 detail_json CLOB NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY(request_id,seq)
);
CREATE TABLE agent_trace_board (
 session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
 board_id VARCHAR(36) NOT NULL, request_id VARCHAR(36) NOT NULL REFERENCES agent_trace_run(request_id) ON DELETE CASCADE,
 PRIMARY KEY(session_id,board_id,request_id)
);
CREATE TABLE agent_trace_client_event (
 session_id VARCHAR(36) NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
 event_id VARCHAR(36) NOT NULL, PRIMARY KEY(session_id,event_id)
);
