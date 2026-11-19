-- 物料关键字搜索的索引补齐（RFC 012 §4.2 加了领猫编码、面料功能以后漏的）。
-- 物料列表、选 SKU 的关键字是一串 OR（编码、名称、领猫编码、货号、成分、面料功能）：任一分支没有索引，
-- 就合不成 BitmapOr，整张表顺序扫。领猫编码照系统编码的做法：btree 管精确查几个，trigram 管包含查。
-- 做法同 V28：Flyway 配套 .conf 置为非事务，CONCURRENTLY 在线建，不挡读写；中断留下的无效索引由下面的检查报出来。
SELECT set_config('lingmao.migration_lock_timeout', current_setting('lock_timeout'), false);
SELECT set_config('lingmao.migration_statement_timeout', current_setting('statement_timeout'), false);
SET lock_timeout = '5s';
SET statement_timeout = '5min';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_legacy_upper ON bas_material(upper(legacy_code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_legacy_trgm ON bas_material USING gin(upper(legacy_code) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_fabric_trgm ON bas_material USING gin(fabric_function gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_sku_legacy_upper ON bas_material_sku(upper(legacy_code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_sku_legacy_trgm ON bas_material_sku USING gin(upper(legacy_code) gin_trgm_ops) WITH (gin_pending_list_limit=256);

ALTER INDEX idx_bas_mat_legacy_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_bas_mat_sku_legacy_upper ALTER COLUMN 1 SET STATISTICS 1000;

ANALYZE bas_material;
ANALYZE bas_material_sku;

DO $verify$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
               WHERE c.relnamespace=current_schema()::regnamespace
               AND c.relname IN ('idx_bas_mat_legacy_upper','idx_bas_mat_legacy_trgm','idx_bas_mat_fabric_trgm',
                                'idx_bas_mat_sku_legacy_upper','idx_bas_mat_sku_legacy_trgm')
               AND (NOT i.indisvalid OR NOT i.indisready)) THEN
        RAISE EXCEPTION '物料搜索索引无效；请 CONCURRENTLY 删除无效的同名索引后重试迁移';
    END IF;
END $verify$;
SELECT set_config('statement_timeout', current_setting('lingmao.migration_statement_timeout'), false);
SELECT set_config('lock_timeout', current_setting('lingmao.migration_lock_timeout'), false);
