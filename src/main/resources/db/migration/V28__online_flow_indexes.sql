-- Flyway 配套 .conf 将本迁移置为非事务；在线建立，不阻断表读写。
-- 失败重试前删除同名无效索引（CREATE INDEX CONCURRENTLY 中断可能遗留 invalid）。
-- 若这些索引存在但 invalid，先由部署预检明确报错，不把 IF NOT EXISTS 的跳过当成成功。
SELECT set_config('lingmao.migration_lock_timeout', current_setting('lock_timeout'), false);
SELECT set_config('lingmao.migration_statement_timeout', current_setting('statement_timeout'), false);
SET lock_timeout = '5s';
SET statement_timeout = '5min';

-- PostgreSQL 自带的 trusted 扩展：编码、单号、名称都按包含查（Keywords），靠 trigram 索引。
-- 部署账号须能在当前数据库 CREATE EXTENSION，或由数据库管理员预装 pg_trgm。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_seek ON inv_stock_log(create_time DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_wh_seek ON inv_stock_log(warehouse_id,create_time DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_sku_seek ON inv_stock_log(material_sku_id,create_time DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_doc_upper ON inv_stock_log(upper(doc_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_doc_trgm ON inv_stock_log USING gin(upper(doc_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
-- 流水单号批量精确查需要更细的直方图，避免把候选数估大而改走全历史扫描。
ALTER INDEX idx_inv_log_doc_upper ALTER COLUMN 1 SET STATISTICS 1000;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mrp_calc_status_time ON mrp_calc(status,create_time DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_order_status_date ON ord_order(status,order_date DESC,id DESC);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pur_order_status_date ON pur_order(status,order_date DESC,id DESC);

-- 高频列表实际 Mapper 的标识分支；upper 的表达式与查询完全一致。
-- 每个编码列两份：_upper（B-tree）给批量精确 IN，_trgm（GIN）给单个词的包含查。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_count_no_upper ON inv_count(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_count_no_trgm ON inv_count USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_no_upper ON inv_doc(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_no_trgm ON inv_doc USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
-- 完整表达式索引提供全表 upper(...) 统计；密集外部号码下避免按缺省选择率估计批量编码。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_source_upper ON inv_doc(upper(source_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_source_trgm ON inv_doc USING gin(upper(source_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_delivery_upper ON inv_doc(upper(supplier_delivery_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_delivery_trgm ON inv_doc USING gin(upper(supplier_delivery_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_logistics_upper ON inv_doc(upper(logistics_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_logistics_trgm ON inv_doc USING gin(upper(logistics_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_doc_item_dye_trgm ON inv_doc_item USING gin(dye_lot gin_trgm_ops)
    WITH (gin_pending_list_limit=256) WHERE dye_lot IS NOT NULL;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_change_no_upper ON ord_change(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_change_no_trgm ON ord_change USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sty_sku_code_upper ON sty_style_sku(upper(code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sty_sku_code_trgm ON sty_style_sku USING gin(upper(code) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_order_no_upper ON ord_order(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_order_no_trgm ON ord_order USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_order_contract_upper ON ord_order(upper(contract_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ord_order_contract_trgm ON ord_order USING gin(upper(contract_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pur_order_no_upper ON pur_order(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_pur_order_no_trgm ON pur_order USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_prd_order_no_upper ON prd_order(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_prd_order_no_trgm ON prd_order USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mrp_calc_no_upper ON mrp_calc(upper(no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mrp_calc_no_trgm ON mrp_calc USING gin(upper(no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_code_upper ON bas_material(upper(code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_code_trgm ON bas_material USING gin(upper(code) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_item_upper ON bas_material(upper(supplier_item_no));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_item_trgm ON bas_material USING gin(upper(supplier_item_no) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_sku_code_upper ON bas_material_sku(upper(code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_sku_code_trgm ON bas_material_sku USING gin(upper(code) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sty_style_code_upper ON sty_style(upper(code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sty_style_code_trgm ON sty_style USING gin(upper(code) gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sup_supplier_code_upper ON sup_supplier(upper(code));
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sup_supplier_code_trgm ON sup_supplier USING gin(upper(code) gin_trgm_ops) WITH (gin_pending_list_limit=256);

-- 稀疏编码在默认 100 个直方图桶下会被估大，进而退化为关联大表全扫描。
-- 提高已验证的编码表达式统计精度；保留规划器选择，不禁用顺扫、索引扫描或连接算法。
ALTER INDEX idx_inv_count_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_inv_doc_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_inv_doc_source_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_inv_doc_delivery_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_inv_doc_logistics_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_ord_change_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_sty_sku_code_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_ord_order_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_ord_order_contract_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_pur_order_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_prd_order_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_mrp_calc_no_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_bas_mat_code_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_bas_mat_item_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_bas_mat_sku_code_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_sty_style_code_upper ALTER COLUMN 1 SET STATISTICS 1000;
ALTER INDEX idx_sup_supplier_code_upper ALTER COLUMN 1 SET STATISTICS 1000;

-- 批量核价逐业务键读取一个现价；同一天可有大量报价，id 必须参与索引排序，避免扫描全部同日历史。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_prc_process_current ON prc_price(supplier_id,style_id,process_mode,effective_date DESC,id DESC)
    WHERE type='PROCESS' AND NOT voided;

-- 同表的名称 OR 由 BitmapOr 合并；关联表 OR 在 Mapper 中先按候选主键 UNION，再执行业务/权限过滤。
-- 保留 fastupdate，但将每个索引的待合并队列限制为 256KiB，避免一次导入后多分支扫描数 MiB 队列。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bas_mat_text_trgm ON bas_material USING gin(name gin_trgm_ops,composition gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sty_style_text_trgm ON sty_style USING gin(name gin_trgm_ops,alias_name gin_trgm_ops,product_name gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sup_supplier_text_trgm ON sup_supplier USING gin(name gin_trgm_ops,short_name gin_trgm_ops,contact gin_trgm_ops) WITH (gin_pending_list_limit=256);
-- 算料的订单号/款号是历史快照：单个词按包含查，批量按完整 token 精确重叠，不能换成当前主档编码。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_inv_log_dye_trgm ON inv_stock_log USING gin(dye_lot gin_trgm_ops) WITH (gin_pending_list_limit=256);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_mrp_item_snapshot_search ON mrp_calc_item USING gin(
    upper(order_nos) gin_trgm_ops,upper(style_codes) gin_trgm_ops,
    string_to_array(upper(order_nos), ',') array_ops,string_to_array(upper(style_codes), ',') array_ops) WITH (gin_pending_list_limit=256);

ANALYZE inv_stock_log;
ANALYZE inv_doc;
ANALYZE inv_count;
ANALYZE ord_order;
ANALYZE ord_change;
ANALYZE sty_style_sku;
ANALYZE pur_order;
ANALYZE prd_order;
ANALYZE mrp_calc;
ANALYZE bas_material;
ANALYZE bas_material_sku;
ANALYZE sty_style;
ANALYZE sup_supplier;

DO $verify$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
               WHERE c.relnamespace=current_schema()::regnamespace
               AND c.relname IN ('idx_inv_log_seek','idx_inv_log_wh_seek','idx_inv_log_sku_seek','idx_inv_log_doc_upper','idx_inv_log_doc_trgm',
                                'idx_mrp_calc_status_time','idx_ord_order_status_date','idx_pur_order_status_date',
                                'idx_ord_order_no_upper','idx_ord_order_no_trgm','idx_ord_order_contract_upper','idx_ord_order_contract_trgm','idx_pur_order_no_upper','idx_pur_order_no_trgm',
                                'idx_prd_order_no_upper','idx_prd_order_no_trgm','idx_mrp_calc_no_upper','idx_mrp_calc_no_trgm','idx_bas_mat_code_upper','idx_bas_mat_code_trgm',
                                'idx_bas_mat_item_upper','idx_bas_mat_item_trgm','idx_bas_mat_sku_code_upper','idx_bas_mat_sku_code_trgm','idx_sty_style_code_upper','idx_sty_style_code_trgm',
                                'idx_sup_supplier_code_upper','idx_sup_supplier_code_trgm','idx_bas_mat_text_trgm','idx_sty_style_text_trgm',
                                'idx_sup_supplier_text_trgm','idx_mrp_item_snapshot_search','idx_inv_log_dye_trgm',
                                'idx_inv_doc_no_upper','idx_inv_doc_no_trgm','idx_inv_doc_source_upper','idx_inv_doc_source_trgm','idx_inv_doc_delivery_upper','idx_inv_doc_delivery_trgm',
                                'idx_inv_doc_logistics_upper','idx_inv_doc_logistics_trgm','idx_inv_doc_item_dye_trgm','idx_inv_count_no_upper','idx_inv_count_no_trgm',
                                'idx_ord_change_no_upper','idx_ord_change_no_trgm','idx_sty_sku_code_upper','idx_sty_sku_code_trgm','idx_prc_process_current')
               AND (NOT i.indisvalid OR NOT i.indisready)) THEN
        RAISE EXCEPTION '生产索引无效；请 CONCURRENTLY 删除无效的同名索引后重试迁移';
    END IF;
END $verify$;
SELECT set_config('statement_timeout', current_setting('lingmao.migration_statement_timeout'), false);
SELECT set_config('lock_timeout', current_setting('lingmao.migration_lock_timeout'), false);
