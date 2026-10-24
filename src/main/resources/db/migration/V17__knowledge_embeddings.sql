CREATE TABLE knowledge_chunk_embeddings (
 chunk_id VARCHAR(36) PRIMARY KEY REFERENCES knowledge_chunks(id),
 version_id VARCHAR(36) NOT NULL, source_id VARCHAR(36) NOT NULL,
 scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, model_name VARCHAR(120) NOT NULL,
 dimensions INTEGER NOT NULL, vector TEXT NOT NULL, embedded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX knowledge_embeddings_scope ON knowledge_chunk_embeddings(scope_kind,scope_id,fingerprint);
CREATE TABLE knowledge_embedding_jobs (
 id VARCHAR(36) PRIMARY KEY, scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 state VARCHAR(16) NOT NULL, progress INTEGER NOT NULL, total INTEGER NOT NULL,
 cancel_requested BOOLEAN NOT NULL, message VARCHAR(500) NOT NULL,
 fingerprint VARCHAR(64) NOT NULL, model_name VARCHAR(120) NOT NULL,
 usage_calls INTEGER NOT NULL, usage_tokens BIGINT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX knowledge_embedding_jobs_recent ON knowledge_embedding_jobs(created_at);
