ALTER TABLE opportunities
    ADD COLUMN IF NOT EXISTS task_id INTEGER
        REFERENCES tasks(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_opportunities_task_id
    ON opportunities(task_id)
    WHERE task_id IS NOT NULL;
