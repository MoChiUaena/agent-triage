CREATE TABLE conversations (
 id VARCHAR(36) PRIMARY KEY, revision BIGINT NOT NULL, active_attempt VARCHAR(36),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 origin_payload TEXT NOT NULL
);
CREATE TABLE conversation_messages (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL REFERENCES conversations(id),
 ordinal INTEGER NOT NULL, attempt_id VARCHAR(36) NOT NULL, payload TEXT NOT NULL,
 UNIQUE(conversation_id,ordinal)
);
CREATE TABLE conversation_attempts (
 id VARCHAR(36) PRIMARY KEY, conversation_id VARCHAR(36) NOT NULL REFERENCES conversations(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, state VARCHAR(32) NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX conversations_recent ON conversations(updated_at,id);
CREATE INDEX conversation_attempts_owner ON conversation_attempts(conversation_id,created_at);
