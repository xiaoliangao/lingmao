-- 库存原事件只能追加；纠错必须新增反向流水，不能改写原数量、估值或追踪关系。
-- 独立于其他审计日志的守卫，可重复安装，不回改已发布的版本迁移。
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

CREATE OR REPLACE FUNCTION scm_inventory_ledger_append_only()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '库存流水只允许追加，不能修改或删除历史事件'
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE TRIGGER inventory_ledger_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON inv_stock_log
    FOR EACH STATEMENT EXECUTE FUNCTION scm_inventory_ledger_append_only();
