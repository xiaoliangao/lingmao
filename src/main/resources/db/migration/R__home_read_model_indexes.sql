-- 首页通知分页与待审提交时间查询。独立 repeatable，不占用其他分支已发布的版本号。
-- 配套 .conf 使用非事务执行；同名 invalid 索引必须由运维修复，禁止静默跳过。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index
        WHERE indexrelid IN (to_regclass('idx_notice_pending_doc_time'), to_regclass('idx_doc_log_latest_submit'))
          AND NOT indisvalid
    ) THEN
        RAISE EXCEPTION 'Home read model index is invalid; repair it before retrying the migration';
    END IF;
END $$;

SELECT set_config('lingmao.home_previous_lock_timeout', current_setting('lock_timeout'), false);
SELECT set_config('lingmao.home_previous_statement_timeout', current_setting('statement_timeout'), false);
SET lock_timeout = '3s';
SET statement_timeout = '5min';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_notice_pending_doc_time
    ON biz_doc_notice (doc_type, doc_id, create_time DESC, id DESC)
    WHERE handled_time IS NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_doc_log_latest_submit
    ON biz_doc_log (doc_type, doc_id, create_time DESC, id DESC)
    WHERE action = 'submit';

SELECT set_config('lock_timeout', current_setting('lingmao.home_previous_lock_timeout'), false);
SELECT set_config('statement_timeout', current_setting('lingmao.home_previous_statement_timeout'), false);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index
        WHERE indexrelid IN (to_regclass('idx_notice_pending_doc_time'), to_regclass('idx_doc_log_latest_submit'))
          AND NOT indisvalid
    ) THEN
        RAISE EXCEPTION 'Home read model index is invalid; repair it before retrying the migration';
    END IF;
END $$;
