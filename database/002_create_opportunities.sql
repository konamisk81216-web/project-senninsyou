CREATE TABLE IF NOT EXISTS opportunities (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    opportunity_type VARCHAR(50) NOT NULL,
    expected_revenue NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (expected_revenue >= 0),
    estimated_minutes INTEGER NOT NULL DEFAULT 0 CHECK (estimated_minutes >= 0),
    risk_level VARCHAR(10) NOT NULL DEFAULT '中'
        CHECK (risk_level IN ('低', '中', '高')),
    status VARCHAR(20) NOT NULL DEFAULT '未評価'
        CHECK (status IN ('未評価', '調査中', '有望', '保留', '却下')),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_opportunities_status
    ON opportunities(status);
