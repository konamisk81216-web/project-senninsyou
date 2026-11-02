-- 本人が直した下書き（修正版）を、AIが書いた下書きと区別する印。
-- 修正版は「完了」の1件として増えるため、印がないと作成本数と平均作成時間（実績の集計）が歪む。
-- 既存の行は既定値の FALSE（AIが書いた下書き）になるので、既存データの補正は要らない。
--
-- 本番では、この列を追加して定義を確かめてから、印を使うコードを反映する（逆の順にすると集計と修正版の保存が失敗する）。
-- 列の追加には表への強いロックが必要で、ほかの読み書きが終わるまで待たされることがある。
-- 待ちすぎないよう上限を置き、上限を超えたら何も変えずに失敗する。そのときは時間をおいて、もう一度実行する。
BEGIN;
SET LOCAL lock_timeout = '5s';
ALTER TABLE writer_jobs
    ADD COLUMN IF NOT EXISTS is_edited BOOLEAN NOT NULL DEFAULT FALSE;
COMMIT;

-- 定義の確認（1行、boolean・NO・false が返ること）：
-- SELECT data_type, is_nullable, column_default
--   FROM information_schema.columns
--  WHERE table_name = 'writer_jobs' AND column_name = 'is_edited';
