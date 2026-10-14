CREATE TABLE ci_reports (
    id VARCHAR(36) PRIMARY KEY,
    repository_id VARCHAR(36) NOT NULL REFERENCES github_repositories(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX ci_reports_repository_time ON ci_reports (repository_id, created_at);
