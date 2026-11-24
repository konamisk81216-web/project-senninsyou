-- 書き方の決まり。本人のダメ出しやAIの指摘を、次の下書きで守る決まりとして残す。
-- 消さずに残す（使わなくなった決まりは active を false にする）。
CREATE TABLE IF NOT EXISTS writing_rules (
    id BIGSERIAL PRIMARY KEY,
    target VARCHAR(20) NOT NULL DEFAULT '共通'
        CHECK (target IN ('共通', 'note記事', 'SNS投稿')),
    rule TEXT NOT NULL,
    reason TEXT NOT NULL DEFAULT '',
    source VARCHAR(10) NOT NULL DEFAULT '本人'
        CHECK (source IN ('本人', 'AIの指摘')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_writing_rules_active_target
    ON writing_rules(active, target);
