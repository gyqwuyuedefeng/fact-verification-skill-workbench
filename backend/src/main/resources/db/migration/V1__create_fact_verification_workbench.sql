CREATE TABLE test.verification_task (
    id uuid PRIMARY KEY,
    request_id varchar(80) NOT NULL,
    original_file_name varchar(255) NOT NULL,
    media_type varchar(120) NOT NULL,
    file_size bigint NOT NULL CHECK (file_size > 0),
    file_hash char(64) NOT NULL,
    upload_path varchar(500) NOT NULL,
    parser_version varchar(80),
    document_snapshot jsonb,
    document_snapshot_hash char(64),
    evidence_snapshot_id uuid,
    status varchar(24) NOT NULL CHECK (status IN ('UPLOADED', 'PARSING', 'READY', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
    shadow_requested boolean NOT NULL DEFAULT false,
    error_code varchar(80),
    error_summary varchar(500),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (request_id)
);

CREATE INDEX idx_verification_task_status_created
    ON test.verification_task (status, created_at DESC);
CREATE INDEX idx_verification_task_file_hash
    ON test.verification_task (file_hash);

CREATE TABLE test.skill_version (
    id uuid PRIMARY KEY,
    skill_key varchar(100) NOT NULL CHECK (skill_key = 'company-material-fact-check'),
    version varchar(40),
    parent_version_id uuid REFERENCES test.skill_version (id),
    status varchar(16) NOT NULL CHECK (status IN ('DRAFT', 'CANDIDATE', 'STABLE', 'ARCHIVED')),
    skill_markdown text NOT NULL,
    references_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    allowed_tools_json jsonb NOT NULL,
    output_schema_json jsonb NOT NULL,
    content_hash char(64),
    change_summary varchar(2000),
    version_card_json jsonb,
    registered_evaluation_id uuid,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    frozen_at timestamptz,
    UNIQUE (skill_key, version),
    UNIQUE (skill_key, content_hash)
);

CREATE TABLE test.evaluation_run (
    id uuid PRIMARY KEY,
    request_id varchar(80) NOT NULL,
    dataset_version varchar(80) NOT NULL,
    dataset_hash char(64) NOT NULL,
    sample_count integer NOT NULL CHECK (sample_count >= 0),
    evidence_snapshot_id uuid NOT NULL,
    run_manifest_json jsonb NOT NULL,
    variants_json jsonb NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'INTERRUPTED')),
    sample_results_json jsonb,
    metrics_json jsonb,
    failures_json jsonb,
    human_corrections_json jsonb,
    gate_status varchar(16) NOT NULL CHECK (gate_status IN ('PENDING', 'PASS', 'FAIL')),
    gate_reasons_json jsonb,
    report_markdown text,
    report_json jsonb,
    created_by varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    finished_at timestamptz,
    UNIQUE (request_id)
);

ALTER TABLE test.skill_version
    ADD CONSTRAINT fk_skill_registered_evaluation
    FOREIGN KEY (registered_evaluation_id) REFERENCES test.evaluation_run (id);

CREATE TABLE test.verification_run (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES test.verification_task (id),
    run_type varchar(16) NOT NULL CHECK (run_type IN ('PRIMARY', 'SHADOW')),
    skill_version_id uuid NOT NULL REFERENCES test.skill_version (id),
    model_config_hash char(64) NOT NULL,
    tool_contract_hash char(64) NOT NULL,
    output_schema_hash char(64) NOT NULL,
    evidence_snapshot_id uuid NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'INTERRUPTED')),
    result_json jsonb,
    tool_calls_json jsonb,
    model_usage_json jsonb,
    duration_ms bigint CHECK (duration_ms >= 0),
    error_code varchar(80),
    error_summary varchar(500),
    shadow_review_status varchar(16) CHECK (shadow_review_status IN ('PENDING', 'PASS', 'FAIL')),
    shadow_review_reason varchar(1000),
    shadow_reviewed_by varchar(100),
    shadow_reviewed_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (task_id, run_type)
);

CREATE TABLE test.claim (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES test.verification_run (id),
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    claim_text text NOT NULL,
    material_locator jsonb NOT NULL,
    normalized_claim jsonb NOT NULL,
    company_id varchar(100),
    company_name varchar(500),
    verification_status varchar(20) NOT NULL CHECK (verification_status IN ('VERIFIED', 'CONFLICT', 'INSUFFICIENT')),
    risk_flags jsonb NOT NULL DEFAULT '[]'::jsonb,
    evidence_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    explanation text NOT NULL,
    requires_human_intervention boolean NOT NULL,
    correction_before jsonb,
    correction_after jsonb,
    correction_reason varchar(1000),
    corrected_by varchar(100),
    corrected_at timestamptz,
    created_at timestamptz NOT NULL,
    UNIQUE (run_id, ordinal)
);

CREATE TABLE test.evidence_snapshot (
    id uuid PRIMARY KEY,
    snapshot_id uuid NOT NULL,
    owner_type varchar(16) NOT NULL CHECK (owner_type IN ('EVALUATION', 'TASK')),
    owner_id uuid NOT NULL,
    tool_name varchar(100) NOT NULL,
    canonical_arguments jsonb NOT NULL,
    arguments_hash char(64) NOT NULL,
    fetched_at timestamptz NOT NULL,
    response_json jsonb,
    error_code varchar(80),
    error_summary varchar(500),
    response_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    CHECK ((response_json IS NOT NULL AND error_code IS NULL) OR (response_json IS NULL AND error_code IS NOT NULL)),
    UNIQUE (snapshot_id, tool_name, arguments_hash)
);

CREATE TABLE test.release_binding (
    id uuid PRIMARY KEY,
    skill_key varchar(100) NOT NULL CHECK (skill_key = 'company-material-fact-check'),
    revision bigint NOT NULL CHECK (revision > 0),
    action varchar(24) NOT NULL CHECK (action IN ('INITIALIZE', 'REGISTER', 'SHADOW_START', 'SHADOW_STOP', 'PROMOTE', 'ROLLBACK')),
    stable_version_id uuid REFERENCES test.skill_version (id),
    candidate_version_id uuid REFERENCES test.skill_version (id),
    previous_stable_version_id uuid REFERENCES test.skill_version (id),
    shadow_enabled boolean NOT NULL,
    evaluation_run_id uuid REFERENCES test.evaluation_run (id),
    state_before jsonb NOT NULL,
    state_after jsonb NOT NULL,
    reason varchar(1000) NOT NULL,
    operator varchar(100) NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (skill_key, revision)
);

CREATE INDEX idx_release_binding_latest
    ON test.release_binding (skill_key, revision DESC);
