CREATE TABLE IF NOT EXISTS writer_jobs (
    id BIGSERIAL PRIMARY KEY,
    status VARCHAR(10) NOT NULL DEFAULT '受付'
        CHECK (status IN ('受付', '執筆中', '完了', '失敗')),
    theme VARCHAR(255) NOT NULL,
    audience VARCHAR(500) NOT NULL,
    price VARCHAR(100) NOT NULL DEFAULT '未定',
    source_notes TEXT NOT NULL,
    interview_notes TEXT,
    title TEXT,
    free_section TEXT,
    paid_section TEXT,
    sales_description TEXT,
    sns_post TEXT,
    review_notes TEXT,
    error_code VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_writer_jobs_created_at
    ON writer_jobs(created_at DESC);
