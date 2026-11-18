-- Expand only；旧草稿重新载入来源后才有冻结采购价，不从已舍入的库存单价倒推。
SET LOCAL lock_timeout = '5s';
ALTER TABLE inv_doc_item ADD COLUMN purchase_price NUMERIC(14,4);
ALTER TABLE inv_doc_item ADD CONSTRAINT ck_inv_doc_purchase_price
    CHECK (purchase_price IS NULL OR (purchase_price >= 0 AND purchase_price::text NOT IN ('NaN','Infinity','-Infinity'))) NOT VALID;
COMMENT ON COLUMN inv_doc_item.purchase_price IS '生成/重载草稿时冻结的元每采购单位单价；分批过账累计金额按此价分配，旧行未知保持NULL';

ALTER TABLE inv_stock_log ADD COLUMN purchase_rounding_version SMALLINT;
ALTER TABLE inv_stock_log ADD CONSTRAINT ck_inv_log_rounding_version CHECK (purchase_rounding_version IS NULL OR purchase_rounding_version=1) NOT VALID;
COMMENT ON COLUMN inv_stock_log.purchase_rounding_version IS '1=持来源锁按累计净采购量分配数量/金额；NULL=旧算法或非采购；反审沿用原流水版本';
