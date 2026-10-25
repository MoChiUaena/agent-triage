CREATE TABLE operation_drafts (
 id VARCHAR(36) PRIMARY KEY, repository_id VARCHAR(36) NOT NULL REFERENCES github_repositories(id) ON DELETE CASCADE,
 state VARCHAR(16) NOT NULL, revision INT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX operation_drafts_recent ON operation_drafts(repository_id, state, updated_at);
