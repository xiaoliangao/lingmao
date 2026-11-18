-- 仅实现已上线库存凭证的期间冻结，不冒充财务月结/应收应付核算。
-- 运维在完成核对后登记 inv_closed_period；关闭记录只增不改，应用没有解除关闭的接口。
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS inv_closed_period (
    company_id BIGINT NOT NULL REFERENCES bas_company(id),
    period_start DATE NOT NULL CHECK (EXTRACT(DAY FROM period_start) = 1),
    closed_by BIGINT NOT NULL REFERENCES sys_user(id),
    closed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    reason VARCHAR(500) NOT NULL CHECK (btrim(reason) <> ''),
    PRIMARY KEY (company_id, period_start)
);
COMMENT ON TABLE inv_closed_period IS '已核对的库存期间冻结；仅库存凭证门禁，不是财务月结计算结果';
CREATE INDEX IF NOT EXISTS idx_inv_closed_period_user ON inv_closed_period(closed_by);

CREATE OR REPLACE FUNCTION inv_period_key(company bigint, business_date date) RETURNS bigint
LANGUAGE sql IMMUTABLE STRICT AS $fn$
    SELECT hashtextextended('inventory-period:' || company::text || ':' || date_trunc('month', business_date::timestamp)::date::text, 0)
$fn$;

CREATE OR REPLACE FUNCTION inv_assert_open_period(company bigint, business_date date) RETURNS void
LANGUAGE plpgsql AS $fn$
BEGIN
    IF company IS NULL OR business_date IS NULL THEN RETURN; END IF;
    -- 写单持共享锁到事务结束；关闭取排它锁，所以不会漏掉“检查后恰好关账”的并发窗口。
    PERFORM pg_advisory_xact_lock_shared(inv_period_key(company,business_date));
    IF EXISTS (SELECT 1 FROM inv_closed_period p WHERE p.company_id=company
               AND p.period_start=date_trunc('month',business_date::timestamp)::date) THEN
        RAISE EXCEPTION USING ERRCODE='P2007', MESSAGE='该库存会计期间已关闭，请在开放期间登记调整，不可回写历史凭证';
    END IF;
END $fn$;

CREATE OR REPLACE FUNCTION inv_freeze_period() RETURNS trigger LANGUAGE plpgsql AS $fn$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION USING ERRCODE='P2007', MESSAGE='已关闭期间只能保留，不允许直接修改或删除关闭记录';
    END IF;
    PERFORM pg_advisory_xact_lock(inv_period_key(NEW.company_id,NEW.period_start));
    RETURN NEW;
END $fn$;
DROP TRIGGER IF EXISTS tr_inv_freeze_period ON inv_closed_period;
CREATE TRIGGER tr_inv_freeze_period BEFORE INSERT OR UPDATE OR DELETE ON inv_closed_period
FOR EACH ROW EXECUTE FUNCTION inv_freeze_period();

CREATE OR REPLACE FUNCTION inv_guard_document_period() RETURNS trigger LANGUAGE plpgsql AS $fn$
DECLARE old_date date; new_date date;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        old_date := (to_jsonb(OLD)->>TG_ARGV[0])::date;
        PERFORM inv_assert_open_period(OLD.company_id,old_date);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        new_date := (to_jsonb(NEW)->>TG_ARGV[0])::date;
        PERFORM inv_assert_open_period(NEW.company_id,new_date);
        RETURN NEW;
    END IF;
    RETURN OLD;
END $fn$;
DROP TRIGGER IF EXISTS tr_inv_doc_period ON inv_doc;
CREATE TRIGGER tr_inv_doc_period BEFORE INSERT OR UPDATE OR DELETE ON inv_doc
FOR EACH ROW EXECUTE FUNCTION inv_guard_document_period('doc_date');
DROP TRIGGER IF EXISTS tr_inv_count_period ON inv_count;
CREATE TRIGGER tr_inv_count_period BEFORE INSERT OR UPDATE OR DELETE ON inv_count
FOR EACH ROW EXECUTE FUNCTION inv_guard_document_period('count_date');

CREATE OR REPLACE FUNCTION inv_guard_log_period() RETURNS trigger LANGUAGE plpgsql AS $fn$
DECLARE company bigint;
BEGIN
    SELECT d.company_id INTO company FROM inv_doc d WHERE d.id=NEW.inv_doc_id;
    IF company IS NULL THEN SELECT c.company_id INTO company FROM inv_count c WHERE c.id=NEW.count_id; END IF;
    PERFORM inv_assert_open_period(company,NEW.biz_date);
    RETURN NEW;
END $fn$;
DROP TRIGGER IF EXISTS tr_inv_log_period ON inv_stock_log;
CREATE TRIGGER tr_inv_log_period BEFORE INSERT ON inv_stock_log FOR EACH ROW EXECUTE FUNCTION inv_guard_log_period();
