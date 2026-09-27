CREATE TABLE model_providers (
    id VARCHAR(36) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    encrypted_key TEXT NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    timeout_seconds INTEGER NOT NULL,
    max_rounds INTEGER NOT NULL,
    max_tokens INTEGER NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE model_selection (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    mode VARCHAR(16) NOT NULL,
    provider_id VARCHAR(36),
    CONSTRAINT fk_selected_provider FOREIGN KEY (provider_id) REFERENCES model_providers(id)
);
