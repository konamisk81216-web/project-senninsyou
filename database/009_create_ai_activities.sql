CREATE TABLE IF NOT EXISTS ai_activities (
    id BIGSERIAL PRIMARY KEY,
    agent VARCHAR(30) NOT NULL,
    action VARCHAR(255) NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT '作業中'
        CHECK (status IN ('作業中', '完了', '失敗')),
    detail TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ai_activities_started_at
    ON ai_activities(started_at DESC);

CREATE INDEX IF NOT EXISTS idx_ai_activities_agent
    ON ai_activities(agent, id DESC);
