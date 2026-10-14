CREATE TABLE development_workflows (
    id VARCHAR(36) PRIMARY KEY,
    repository_id VARCHAR(36) NOT NULL REFERENCES github_repositories(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX development_workflows_repository_time ON development_workflows (repository_id, created_at);
