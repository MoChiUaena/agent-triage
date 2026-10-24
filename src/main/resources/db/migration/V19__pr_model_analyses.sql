CREATE TABLE pr_model_analyses (
 id VARCHAR(36) PRIMARY KEY, report_id VARCHAR(36) NOT NULL, repository_id VARCHAR(36) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, payload TEXT NOT NULL
);
CREATE INDEX pr_model_analyses_report ON pr_model_analyses(report_id, created_at);
