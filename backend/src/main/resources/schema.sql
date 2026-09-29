CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS providers (
    id            UUID PRIMARY KEY,
    name          TEXT NOT NULL,
    contract_ref  TEXT,
    sla_days      INT
);

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY,
    email         TEXT UNIQUE NOT NULL,
    display_name  TEXT NOT NULL,
    role          TEXT NOT NULL CHECK (role IN ('METIER', 'PROJET', 'PRESTATAIRE')),
    password_hash TEXT NOT NULL,
    provider_id   UUID REFERENCES providers(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS documents (
    id            UUID PRIMARY KEY,
    title         TEXT NOT NULL,
    doc_type      TEXT NOT NULL CHECK (doc_type IN ('EB', 'CDC')),
    project_code  TEXT,
    provider_id   UUID REFERENCES providers(id),
    current_state TEXT NOT NULL,
    created_by    UUID NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS document_versions (
    id            UUID PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents(id),
    version_num   INT NOT NULL,
    content       TEXT NOT NULL,
    author_id     UUID NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, version_num)
);

-- Journal d'audit : écriture seule, jamais modifié, jamais supprimé
CREATE TABLE IF NOT EXISTS workflow_transitions (
    id            BIGSERIAL PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents(id),
    from_state    TEXT NOT NULL,
    to_state      TEXT NOT NULL,
    action        TEXT NOT NULL,
    actor_id      UUID NOT NULL REFERENCES users(id),
    actor_role    TEXT NOT NULL,
    comment       TEXT,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_transitions_doc
    ON workflow_transitions(document_id, occurred_at);

CREATE TABLE IF NOT EXISTS clarification_requests (
    id            UUID PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents(id),
    provider_id   UUID NOT NULL REFERENCES providers(id),
    requested_by  UUID NOT NULL REFERENCES users(id),
    question      TEXT NOT NULL,
    section_ref   TEXT,
    requested_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    resolution    TEXT,
    days_since_provider_received NUMERIC
);

CREATE TABLE IF NOT EXISTS rag_chunks (
    id            BIGSERIAL PRIMARY KEY,
    document_id   UUID REFERENCES documents(id),
    source_label  TEXT NOT NULL,
    section_title TEXT,
    chunk_text    TEXT NOT NULL,
    embedding     vector(768),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON rag_chunks
    USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS rag_jobs (
    id            UUID PRIMARY KEY,
    document_id   UUID NOT NULL REFERENCES documents(id),
    job_type      TEXT NOT NULL CHECK (job_type IN ('COMPLETENESS_CHECK', 'SUGGESTION')),
    status        TEXT NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    input_ref     TEXT,
    result        TEXT,
    error         TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
