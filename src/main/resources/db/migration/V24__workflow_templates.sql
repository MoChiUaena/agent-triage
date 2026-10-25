CREATE TABLE workflow_templates (
 id VARCHAR(36) PRIMARY KEY, repository_id VARCHAR(36) NOT NULL REFERENCES github_repositories(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX workflow_templates_recent ON workflow_templates(repository_id, updated_at);
