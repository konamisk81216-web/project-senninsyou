CREATE TABLE IF NOT EXISTS content_metrics (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    measured_on DATE NOT NULL,
    views INTEGER NOT NULL DEFAULT 0 CHECK (views >= 0),
    clicks INTEGER NOT NULL DEFAULT 0 CHECK (clicks >= 0),
    purchases INTEGER NOT NULL DEFAULT 0 CHECK (purchases >= 0),
    memo TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_content_metrics_measured_on
    ON content_metrics(measured_on DESC);

CREATE TABLE IF NOT EXISTS monthly_costs (
    id BIGSERIAL PRIMARY KEY,
    month VARCHAR(7) NOT NULL,
    service VARCHAR(100) NOT NULL,
    amount NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (amount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_monthly_costs_month
    ON monthly_costs(month DESC);
