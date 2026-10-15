CREATE TABLE knowledge_sources (
 id VARCHAR(36) PRIMARY KEY, scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 origin VARCHAR(16) NOT NULL, root_path VARCHAR(2048) NOT NULL, locator_hash VARCHAR(64) NOT NULL,
 commit_sha VARCHAR(40), revision BIGINT NOT NULL, state VARCHAR(16) NOT NULL,
 manifest_hash VARCHAR(64) NOT NULL, imported_at TIMESTAMP WITH TIME ZONE NOT NULL, coverage TEXT NOT NULL,
 UNIQUE(scope_kind,scope_id,origin,locator_hash)
);
CREATE TABLE knowledge_versions (
 id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL REFERENCES knowledge_sources(id),
 scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL, origin VARCHAR(16) NOT NULL,
 root_path VARCHAR(2048) NOT NULL, commit_sha VARCHAR(40), path VARCHAR(1024) NOT NULL,
 content_hash VARCHAR(64) NOT NULL, title VARCHAR(200) NOT NULL, document_text TEXT NOT NULL,
 imported_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(source_id,path,content_hash)
);
CREATE TABLE knowledge_chunks (
 id VARCHAR(36) PRIMARY KEY, version_id VARCHAR(36) NOT NULL REFERENCES knowledge_versions(id),
 ordinal INTEGER NOT NULL, start_line INTEGER NOT NULL, end_line INTEGER NOT NULL,
 start_offset INTEGER NOT NULL, end_offset INTEGER NOT NULL, content_hash VARCHAR(64) NOT NULL, chunk_text TEXT NOT NULL,
 UNIQUE(version_id,ordinal)
);
CREATE TABLE knowledge_active_documents (
 source_id VARCHAR(36) NOT NULL REFERENCES knowledge_sources(id), path VARCHAR(1024) NOT NULL,
 version_id VARCHAR(36) NOT NULL REFERENCES knowledge_versions(id), PRIMARY KEY(source_id,path)
);
CREATE TABLE knowledge_jobs (
 id VARCHAR(36) PRIMARY KEY, source_id VARCHAR(36) NOT NULL, scope_kind VARCHAR(16) NOT NULL, scope_id VARCHAR(36) NOT NULL,
 state VARCHAR(16) NOT NULL, progress INTEGER NOT NULL, total INTEGER NOT NULL, cancel_requested BOOLEAN NOT NULL,
 message VARCHAR(500) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX knowledge_scope ON knowledge_sources(scope_kind,scope_id);
CREATE INDEX knowledge_versions_source ON knowledge_versions(source_id,imported_at);
CREATE INDEX knowledge_jobs_recent ON knowledge_jobs(created_at);
