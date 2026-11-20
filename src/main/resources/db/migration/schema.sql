-- Bonded material ledger schema. Idempotent bootstrap executed on every start.
-- All names are unqualified; the JDBC connection selects the schema via currentSchema.

CREATE TABLE IF NOT EXISTS ledger (
    id          BIGSERIAL PRIMARY KEY,
    code        TEXT NOT NULL UNIQUE,
    name        TEXT NOT NULL,
    zone        TEXT NOT NULL DEFAULT 'Asia/Shanghai',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS material (
    id          BIGSERIAL PRIMARY KEY,
    ledger_id   BIGINT NOT NULL REFERENCES ledger(id),
    code        TEXT NOT NULL,
    name        TEXT NOT NULL,
    base_unit   TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ledger_id, code)
);

-- Effective-dated source item code -> canonical material.
CREATE TABLE IF NOT EXISTS material_alias (
    id               BIGSERIAL PRIMARY KEY,
    ledger_id        BIGINT NOT NULL REFERENCES ledger(id),
    source_system    TEXT,
    source_item_code TEXT NOT NULL,
    material_id      BIGINT NOT NULL REFERENCES material(id),
    valid_from       DATE NOT NULL,
    valid_to         DATE,
    note             TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX IF NOT EXISTS idx_alias_lookup
    ON material_alias (ledger_id, source_item_code, valid_from DESC);

-- Effective-dated unit -> base unit factor per material.
CREATE TABLE IF NOT EXISTS unit_conversion (
    id          BIGSERIAL PRIMARY KEY,
    ledger_id   BIGINT NOT NULL REFERENCES ledger(id),
    material_id BIGINT NOT NULL REFERENCES material(id),
    from_unit   TEXT NOT NULL,
    factor      NUMERIC(28,9) NOT NULL CHECK (factor > 0),
    valid_from  DATE NOT NULL,
    valid_to    DATE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX IF NOT EXISTS idx_conversion_lookup
    ON unit_conversion (ledger_id, material_id, from_unit, valid_from DESC);

-- Effective-dated processing (single-consumption) relation: output material <- input material.
CREATE TABLE IF NOT EXISTS processing_relation (
    id                 BIGSERIAL PRIMARY KEY,
    ledger_id          BIGINT NOT NULL REFERENCES ledger(id),
    output_material_id BIGINT NOT NULL REFERENCES material(id),
    input_material_id  BIGINT NOT NULL REFERENCES material(id),
    qty_per_output     NUMERIC(28,9) NOT NULL CHECK (qty_per_output > 0),
    valid_from         DATE NOT NULL,
    valid_to           DATE,
    note               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CHECK (output_material_id <> input_material_id)
);
CREATE INDEX IF NOT EXISTS idx_processing_lookup
    ON processing_relation (ledger_id, output_material_id, valid_from DESC);

-- Immutable intake envelope. Every received version is kept.
CREATE TABLE IF NOT EXISTS document (
    id                     BIGSERIAL PRIMARY KEY,
    ledger_id              BIGINT NOT NULL REFERENCES ledger(id),
    doc_type               TEXT NOT NULL,
    source_system          TEXT NOT NULL,
    external_id            TEXT NOT NULL,
    source_version         INTEGER NOT NULL CHECK (source_version > 0),
    business_time          TIMESTAMPTZ NOT NULL,
    received_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload                JSONB NOT NULL,
    payload_text           TEXT NOT NULL,
    request_original       TEXT NOT NULL,
    payload_hash           TEXT NOT NULL,
    idempotency_key        TEXT,
    supersedes_document_id BIGINT REFERENCES document(id),
    void_of_document_id    BIGINT REFERENCES document(id),
    correction_reason      TEXT,
    effective              BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (ledger_id, source_system, doc_type, external_id, source_version),
    UNIQUE (ledger_id, idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_document_effective
    ON document (ledger_id, effective, business_time);
CREATE INDEX IF NOT EXISTS idx_document_key
    ON document (ledger_id, source_system, doc_type, external_id, source_version DESC);

-- Extracted lines with the mapping used at intake (advisory; closing re-resolves by business date).
CREATE TABLE IF NOT EXISTS document_line (
    id               BIGSERIAL PRIMARY KEY,
    document_id      BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    line_no          INTEGER NOT NULL,
    source_item_code TEXT,
    source_unit      TEXT,
    source_quantity  NUMERIC(28,6),
    batch_no         TEXT,
    work_order       TEXT,
    material_id      BIGINT REFERENCES material(id),
    base_quantity    NUMERIC(28,6),
    alias_id         BIGINT,
    conversion_id    BIGINT,
    output_material_code TEXT,
    extra            JSONB NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (document_id, line_no)
);
CREATE INDEX IF NOT EXISTS idx_document_line_doc ON document_line (document_id);
CREATE INDEX IF NOT EXISTS idx_document_line_material ON document_line (material_id);

-- One row per close attempt outcome; versions of the same period are immutable.
CREATE TABLE IF NOT EXISTS period_close (
    id                    BIGSERIAL PRIMARY KEY,
    ledger_id             BIGINT NOT NULL REFERENCES ledger(id),
    period                TEXT NOT NULL,
    version               INTEGER NOT NULL,
    status                TEXT NOT NULL,
    mode                  TEXT NOT NULL,
    algorithm_version     TEXT NOT NULL,
    prior_close_id        BIGINT REFERENCES period_close(id),
    adjustment_of_close_id BIGINT REFERENCES period_close(id),
    input_hash            TEXT NOT NULL,
    result_hash           TEXT NOT NULL,
    snapshot              JSONB NOT NULL,
    document_count        INTEGER NOT NULL,
    closed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_by             TEXT NOT NULL,
    note                  TEXT,
    UNIQUE (ledger_id, period, version),
    CHECK (status IN ('CLOSED', 'SUPERSEDED', 'STALE'))
);
CREATE INDEX IF NOT EXISTS idx_close_period ON period_close (ledger_id, period, version DESC);

CREATE TABLE IF NOT EXISTS close_material_result (
    close_id           BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    material_id        BIGINT NOT NULL REFERENCES material(id),
    opening_quantity   NUMERIC(28,6) NOT NULL,
    inbound_quantity   NUMERIC(28,6) NOT NULL,
    consumed_quantity  NUMERIC(28,6) NOT NULL,
    outbound_quantity  NUMERIC(28,6) NOT NULL,
    outbound_content   NUMERIC(28,6) NOT NULL DEFAULT 0,
    adjust_quantity    NUMERIC(28,6) NOT NULL,
    closing_quantity   NUMERIC(28,6) NOT NULL,
    wip_opening        NUMERIC(28,6) NOT NULL DEFAULT 0,
    wip_closing        NUMERIC(28,6) NOT NULL DEFAULT 0,
    cumulative_consumed NUMERIC(28,6) NOT NULL DEFAULT 0,
    cumulative_outbound_content NUMERIC(28,6) NOT NULL DEFAULT 0,
    allocation_count   INTEGER NOT NULL,
    PRIMARY KEY (close_id, material_id)
);

CREATE TABLE IF NOT EXISTS close_batch_allocation (
    id                BIGSERIAL PRIMARY KEY,
    close_id          BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    ledger_id         BIGINT NOT NULL,
    material_id       BIGINT NOT NULL REFERENCES material(id),
    lot_key           TEXT NOT NULL,
    batch_no          TEXT NOT NULL,
    pool              TEXT NOT NULL DEFAULT 'STOCK',
    movement_type     TEXT NOT NULL,
    document_id       BIGINT REFERENCES document(id),
    line_no           INTEGER,
    work_order        TEXT,
    quantity          NUMERIC(28,6) NOT NULL CHECK (quantity > 0),
    allocation_seq    INTEGER NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_allocation_close ON close_batch_allocation (close_id, active);
CREATE INDEX IF NOT EXISTS idx_allocation_lot ON close_batch_allocation (ledger_id, lot_key, active);

CREATE TABLE IF NOT EXISTS close_lot_state (
    close_id           BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    lot_key            TEXT NOT NULL,
    material_id        BIGINT NOT NULL REFERENCES material(id),
    batch_no           TEXT NOT NULL,
    pool               TEXT NOT NULL DEFAULT 'STOCK',
    quantity           NUMERIC(28,6) NOT NULL,
    remaining          NUMERIC(28,6) NOT NULL CHECK (remaining >= 0),
    business_date      DATE NOT NULL,
    source_document_id BIGINT,
    source_type        TEXT NOT NULL,
    PRIMARY KEY (close_id, lot_key)
);

-- Document versions included in a close, for change detection and audit.
CREATE TABLE IF NOT EXISTS close_document (
    close_id       BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    document_id    BIGINT NOT NULL REFERENCES document(id),
    source_version INTEGER NOT NULL,
    doc_type       TEXT NOT NULL,
    external_id    TEXT NOT NULL,
    business_time  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (close_id, document_id)
);

CREATE TABLE IF NOT EXISTS close_change (
    id                 BIGSERIAL PRIMARY KEY,
    close_id           BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    kind               TEXT NOT NULL,
    document_id        BIGINT,
    prior_document_id  BIGINT,
    doc_type           TEXT,
    external_id        TEXT,
    business_time      TIMESTAMPTZ,
    detail             JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS close_material_adjustment (
    close_id       BIGINT NOT NULL REFERENCES period_close(id) ON DELETE CASCADE,
    material_id    BIGINT NOT NULL REFERENCES material(id),
    quantity_delta NUMERIC(28,6) NOT NULL,
    reason         TEXT NOT NULL,
    PRIMARY KEY (close_id, material_id)
);

CREATE TABLE IF NOT EXISTS discrepancy (
    id             BIGSERIAL PRIMARY KEY,
    ledger_id      BIGINT NOT NULL REFERENCES ledger(id),
    period         TEXT NOT NULL,
    close_id       BIGINT REFERENCES period_close(id),
    kind           TEXT NOT NULL,
    material_id    BIGINT REFERENCES material(id),
    source_system  TEXT,
    source_item_code TEXT,
    expected       NUMERIC(28,6),
    actual         NUMERIC(28,6),
    message        TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'OPEN',
    resolution_note TEXT,
    resolution     TEXT,
    resolved_by    TEXT,
    resolved_at    TIMESTAMPTZ,
    adjustment_document_id BIGINT REFERENCES document(id),
    detail         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (status IN ('OPEN', 'RESOLVED', 'WAIVED'))
);
CREATE INDEX IF NOT EXISTS idx_discrepancy_query ON discrepancy (ledger_id, period, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_discrepancy_pending
    ON discrepancy (close_id, kind, COALESCE(source_item_code, ''), COALESCE(material_id, 0))
    WHERE status IN ('OPEN');

CREATE TABLE IF NOT EXISTS sample_replay (
    id          BIGSERIAL PRIMARY KEY,
    ledger_code TEXT NOT NULL,
    scenario    TEXT NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    detail      JSONB NOT NULL
);

-- Forward-compatible upgrades for databases created by an earlier build. CREATE TABLE IF NOT
-- EXISTS does not add columns to an existing table, so each later column is added defensively.
ALTER TABLE IF EXISTS document ADD COLUMN IF NOT EXISTS payload_text TEXT;
ALTER TABLE IF EXISTS document ADD COLUMN IF NOT EXISTS request_original TEXT;
ALTER TABLE IF EXISTS close_material_result ADD COLUMN IF NOT EXISTS outbound_content NUMERIC(28,6) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS close_material_result ADD COLUMN IF NOT EXISTS wip_opening NUMERIC(28,6) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS close_material_result ADD COLUMN IF NOT EXISTS wip_closing NUMERIC(28,6) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS close_material_result ADD COLUMN IF NOT EXISTS cumulative_consumed NUMERIC(28,6) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS close_material_result ADD COLUMN IF NOT EXISTS cumulative_outbound_content NUMERIC(28,6) NOT NULL DEFAULT 0;
ALTER TABLE IF EXISTS close_batch_allocation ADD COLUMN IF NOT EXISTS pool TEXT NOT NULL DEFAULT 'STOCK';
ALTER TABLE IF EXISTS close_batch_allocation ADD COLUMN IF NOT EXISTS line_no INTEGER;
ALTER TABLE IF EXISTS close_lot_state ADD COLUMN IF NOT EXISTS pool TEXT NOT NULL DEFAULT 'STOCK';
