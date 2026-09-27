CREATE TABLE triage_runs (
    id VARCHAR(36) PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX idx_triage_runs_created_at ON triage_runs (created_at);
CREATE INDEX idx_triage_runs_status ON triage_runs (status);
