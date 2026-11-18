SELECT set_config('lingmao.v35_lock_timeout', current_setting('lock_timeout'), false);
SELECT set_config('lingmao.v35_statement_timeout', current_setting('statement_timeout'), false);
SET lock_timeout = '5s';
SET statement_timeout = '5min';
ALTER TABLE sys_deployment_namespace ADD COLUMN IF NOT EXISTS export_store_uuid UUID;
COMMENT ON COLUMN sys_deployment_namespace.export_store_uuid IS '所有应用实例必须挂载同一私有导出目录；首次注册后身份不自动替换';
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_cache_cleanup ON sys_outbox(tenant_key, updated_at, id) WHERE kind='CACHE_INVALIDATE' AND status='SUCCEEDED';
DO $verify$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
               WHERE c.relnamespace=current_schema()::regnamespace AND c.relname='idx_outbox_cache_cleanup'
                 AND (NOT i.indisvalid OR NOT i.indisready)) THEN
        RAISE EXCEPTION 'Outbox 清理索引无效；请 CONCURRENTLY 删除无效同名索引后重试迁移';
    END IF;
END $verify$;
SELECT set_config('statement_timeout', current_setting('lingmao.v35_statement_timeout'), false);
SELECT set_config('lock_timeout', current_setting('lingmao.v35_lock_timeout'), false);
