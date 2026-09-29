CREATE TABLE source_projects (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    service_id VARCHAR(64) NOT NULL UNIQUE,
    root_path VARCHAR(2048) NOT NULL,
    revision BIGINT NOT NULL,
    snapshot TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    share_provider_id VARCHAR(36),
    share_provider_version BIGINT,
    share_model VARCHAR(160),
    share_selection VARCHAR(240)
);
