-- note販売の進行段階をタスクと共に永続保存する。
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS note_stage VARCHAR(20) DEFAULT '企画';

UPDATE tasks SET note_stage = '企画' WHERE note_stage IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tasks_note_stage_check') THEN
        ALTER TABLE tasks
            ADD CONSTRAINT tasks_note_stage_check
            CHECK (note_stage IN ('企画', '制作', '公開', '販売', '実績'));
    END IF;
END $$;
