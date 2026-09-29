ALTER TABLE triage_runs ADD COLUMN endpoint_id VARCHAR(35);
ALTER TABLE triage_runs ADD COLUMN endpoint_http_method VARCHAR(8);
ALTER TABLE triage_runs ADD COLUMN endpoint_route VARCHAR(160);
ALTER TABLE triage_runs ADD COLUMN endpoint_handler_class VARCHAR(240);
ALTER TABLE triage_runs ADD COLUMN endpoint_handler_method VARCHAR(80);
ALTER TABLE triage_runs ADD COLUMN history_version INTEGER NOT NULL DEFAULT 0;
CREATE INDEX idx_triage_history_endpoint ON triage_runs (service_id, endpoint_id, created_at DESC, id DESC);
