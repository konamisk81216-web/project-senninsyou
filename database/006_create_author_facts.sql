CREATE TABLE IF NOT EXISTS author_facts (
    id BIGSERIAL PRIMARY KEY,
    category VARCHAR(20) NOT NULL DEFAULT 'その他'
        CHECK (category IN ('経歴', '実績', '道具', '失敗', '方針', 'その他')),
    topic VARCHAR(255) NOT NULL,
    fact TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_author_facts_category
    ON author_facts(category);
