CREATE TABLE search_model_providers (
 id VARCHAR(36) PRIMARY KEY, kind VARCHAR(16) NOT NULL, display_name VARCHAR(80) NOT NULL,
 base_url VARCHAR(512) NOT NULL, model_name VARCHAR(120) NOT NULL, encrypted_key TEXT NOT NULL,
 timeout_seconds INTEGER NOT NULL, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(kind)
);
