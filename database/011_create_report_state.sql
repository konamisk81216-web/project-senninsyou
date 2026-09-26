CREATE TABLE IF NOT EXISTS report_state (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    last_reported_at TIMESTAMPTZ,
    last_signature TEXT
);

INSERT INTO report_state (id, last_reported_at, last_signature)
VALUES (1, NULL, NULL)
ON CONFLICT (id) DO NOTHING;
