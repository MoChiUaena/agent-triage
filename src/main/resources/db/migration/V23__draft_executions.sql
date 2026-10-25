CREATE TABLE draft_executions (
 id VARCHAR(36) PRIMARY KEY, repository_id VARCHAR(36) NOT NULL REFERENCES github_repositories(id) ON DELETE CASCADE,
 draft_id VARCHAR(36) NOT NULL REFERENCES operation_drafts(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX draft_executions_recent ON draft_executions(repository_id, created_at);
