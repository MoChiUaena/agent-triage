CREATE TABLE memory_heads (
 id VARCHAR(36) PRIMARY KEY, scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL, status VARCHAR(16) NOT NULL, topic VARCHAR(120) NOT NULL,
 body_sha256 VARCHAR(64) NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE memory_versions (
 memory_id VARCHAR(36) NOT NULL REFERENCES memory_heads(id), revision BIGINT NOT NULL,
 payload TEXT NOT NULL, PRIMARY KEY(memory_id,revision)
);
CREATE INDEX memory_scope_recent ON memory_heads(scope_kind,scope_id,status,updated_at);
