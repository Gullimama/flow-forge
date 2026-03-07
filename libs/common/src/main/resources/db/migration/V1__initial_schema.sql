-- Snapshot tracking
CREATE TABLE snapshots (
    snapshot_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_url        TEXT NOT NULL,
    branch          TEXT NOT NULL DEFAULT 'master',
    commit_sha      TEXT NOT NULL,
    snapshot_type   TEXT NOT NULL CHECK (snapshot_type IN ('BASELINE', 'REFRESH')),
    parent_snapshot UUID REFERENCES snapshots(snapshot_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    changed_files   JSONB DEFAULT '[]'::jsonb,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    metadata        JSONB DEFAULT '{}'::jsonb
);

-- Blob ingestion tracking
CREATE TABLE blob_ingestion_batches (
    batch_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_account TEXT NOT NULL,
    container       TEXT NOT NULL,
    prefix          TEXT NOT NULL DEFAULT '',
    mode            TEXT NOT NULL CHECK (mode IN ('FULL', 'INCREMENTAL')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    blob_count      INTEGER DEFAULT 0,
    metadata        JSONB DEFAULT '{}'::jsonb
);

CREATE TABLE blob_records (
    id              BIGSERIAL PRIMARY KEY,
    batch_id        UUID NOT NULL REFERENCES blob_ingestion_batches(batch_id),
    blob_name       TEXT NOT NULL,
    etag            TEXT NOT NULL,
    content_length  BIGINT NOT NULL,
    last_modified   TIMESTAMPTZ NOT NULL,
    log_type        TEXT CHECK (log_type IN ('APP', 'ISTIO', 'UNKNOWN')),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'DOWNLOADED', 'EXTRACTED', 'PARSED', 'FAILED')),
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, blob_name, etag)
);

-- Job tracking
CREATE TABLE jobs (
    job_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_type        TEXT NOT NULL,
    snapshot_id     UUID REFERENCES snapshots(snapshot_id),
    parent_job      UUID REFERENCES jobs(job_id),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    progress_pct    REAL DEFAULT 0.0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    input_params    JSONB DEFAULT '{}'::jsonb,
    output_refs     JSONB DEFAULT '{}'::jsonb,
    metadata        JSONB DEFAULT '{}'::jsonb,
    version         BIGINT NOT NULL DEFAULT 0
);

-- Research run tracking
CREATE TABLE research_runs (
    run_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id     UUID NOT NULL REFERENCES snapshots(snapshot_id),
    blob_batch_id   UUID REFERENCES blob_ingestion_batches(batch_id),
    job_id          UUID NOT NULL REFERENCES jobs(job_id),
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    models_manifest JSONB DEFAULT '{}'::jsonb,
    pipeline_config JSONB DEFAULT '{}'::jsonb,
    quality_metrics JSONB DEFAULT '{}'::jsonb,
    output_path     TEXT
);

-- Parser artifact tracking
CREATE TABLE parse_artifacts (
    id              BIGSERIAL PRIMARY KEY,
    snapshot_id     UUID NOT NULL REFERENCES snapshots(snapshot_id),
    artifact_type   TEXT NOT NULL,
    artifact_key    TEXT NOT NULL,
    content_hash    TEXT NOT NULL,
    minio_path      TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'PARSED', 'INDEXED', 'EMBEDDED', 'FAILED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata        JSONB DEFAULT '{}'::jsonb,
    UNIQUE (snapshot_id, artifact_type, artifact_key)
);

-- Indexes
CREATE INDEX idx_snapshots_status ON snapshots(status);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_type ON jobs(job_type);
CREATE INDEX idx_jobs_snapshot ON jobs(snapshot_id);
CREATE INDEX idx_blob_records_batch ON blob_records(batch_id);
CREATE INDEX idx_blob_records_status ON blob_records(batch_id, status);
CREATE INDEX idx_parse_artifacts_snapshot ON parse_artifacts(snapshot_id);
CREATE INDEX idx_research_runs_status ON research_runs(status);
