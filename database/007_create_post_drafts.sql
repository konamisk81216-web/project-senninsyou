CREATE TABLE IF NOT EXISTS post_drafts (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(10) NOT NULL DEFAULT '承認待ち'
        CHECK (status IN ('承認待ち', '承認済み', '却下')),
    content TEXT NOT NULL,
    intent VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_post_drafts_status
    ON post_drafts(status, id DESC);
