CREATE TABLE IF NOT EXISTS conversations (
    id BIGSERIAL PRIMARY KEY,
    role VARCHAR(10) NOT NULL CHECK (role IN ('利用者', 'AI将軍')),
    message TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_conversations_created_at
    ON conversations(id DESC);
