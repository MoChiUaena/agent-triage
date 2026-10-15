CREATE TABLE github_repositories (
    id VARCHAR(36) PRIMARY KEY,
    owner_name VARCHAR(39) NOT NULL,
    repository_name VARCHAR(100) NOT NULL,
    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshot TEXT NOT NULL,
    last_error VARCHAR(500),
    UNIQUE (owner_name, repository_name)
);
