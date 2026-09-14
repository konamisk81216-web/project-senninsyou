CREATE TABLE IF NOT EXISTS revenue_records (
    id BIGSERIAL PRIMARY KEY,
    task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
    description VARCHAR(255) NOT NULL,
    revenue NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (revenue >= 0),
    expense NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (expense >= 0),
    profit NUMERIC(14, 2)
        GENERATED ALWAYS AS (revenue - expense) STORED,
    work_minutes INTEGER NOT NULL DEFAULT 0 CHECK (work_minutes >= 0),
    occurred_on DATE NOT NULL DEFAULT CURRENT_DATE,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_revenue_records_task_id
    ON revenue_records(task_id);

CREATE INDEX IF NOT EXISTS idx_revenue_records_occurred_on
    ON revenue_records(occurred_on DESC);
