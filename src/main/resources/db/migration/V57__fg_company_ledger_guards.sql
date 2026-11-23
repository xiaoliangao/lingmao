-- V57：在原样 V46 成品域上补主体库存、严格来源关系和不可改库存账。
-- 这是有界锁等待的维护窗口迁移，绝不宣称对已有大表零停机。
-- V46 之前的共享库存只有能由完整流水证明归属时才能自动升级；混主体/缺流水先人工对账。
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '120s';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM fg_stock WHERE location_id <> 0)
       OR EXISTS (SELECT 1 FROM fg_stock_log WHERE location_id <> 0) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: nonzero locations have no matching document item model; reconcile before V57';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fg_stock_log l JOIN fg_doc d ON d.id = l.fg_doc_id
        GROUP BY l.warehouse_id, l.style_sku_id, l.location_id
        HAVING count(DISTINCT d.company_id) > 1
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: shared stock history contains multiple companies; reconcile ownership before V57';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fg_stock s LEFT JOIN (
            SELECT warehouse_id, style_sku_id, location_id, sum(change_qty) AS qty
            FROM fg_stock_log GROUP BY warehouse_id, style_sku_id, location_id
        ) l USING (warehouse_id, style_sku_id, location_id)
        WHERE l.qty IS NULL OR s.qty <> l.qty
    ) OR EXISTS (
        SELECT 1 FROM fg_stock_log l LEFT JOIN fg_stock s USING (warehouse_id, style_sku_id, location_id)
        WHERE s.id IS NULL
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: stock balance has no complete matching ledger';
    END IF;
    IF EXISTS (
        SELECT 1 FROM (
            SELECT balance_qty, sum(change_qty) OVER (
                PARTITION BY warehouse_id, style_sku_id, location_id ORDER BY id ROWS UNBOUNDED PRECEDING
            ) AS expected_qty FROM fg_stock_log
        ) l WHERE balance_qty <> expected_qty OR expected_qty < 0
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: historical ledger balance is inconsistent';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fg_doc d LEFT JOIN prd_receipt r ON r.id = d.prd_receipt_id
        LEFT JOIN ord_order o ON o.id = d.order_id
        WHERE (d.prd_receipt_id IS NOT NULL AND d.company_id <> r.company_id)
           OR (d.order_id IS NOT NULL AND d.company_id <> o.company_id)
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: source document company mismatch';
    END IF;
    -- V46 的反审没有显式引用：只允许由同单、同库存键紧前原事件且数量相反唯一证明的冲销。
    IF EXISTS (
        SELECT 1 FROM (
            SELECT l.*, lag(change_qty) OVER w AS previous_qty,
                   lag(change_qty::bigint * CASE WHEN d.kind = 'IN' THEN 1 ELSE -1 END) OVER w AS previous_direction,
                   lag(doc_no) OVER w AS previous_no,lag(change_type) OVER w AS previous_type,lag(biz_date) OVER w AS previous_date,
                   change_qty::bigint * CASE WHEN d.kind = 'IN' THEN 1 ELSE -1 END AS direction
            FROM fg_stock_log l JOIN fg_doc d ON d.id = l.fg_doc_id
            WINDOW w AS (PARTITION BY fg_doc_id, warehouse_id, style_sku_id, location_id ORDER BY l.id)
        ) l WHERE change_qty=0 OR (direction>0 AND previous_direction>0)
           OR (direction<0 AND (previous_direction IS NULL OR previous_direction<=0 OR previous_qty::bigint <> -change_qty::bigint
               OR (previous_no,previous_type,previous_date) IS DISTINCT FROM (doc_no,change_type,biz_date)))
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: historical reversals cannot be paired uniquely';
    END IF;
END;
$$;

ALTER TABLE fg_stock ADD COLUMN company_id BIGINT;
ALTER TABLE fg_stock ADD COLUMN movement_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE fg_stock_log ADD COLUMN company_id BIGINT;
ALTER TABLE fg_stock_log ADD COLUMN reversal_of_log_id BIGINT REFERENCES fg_stock_log(id);
ALTER TABLE fg_stock_log ADD COLUMN request_id VARCHAR(64);
-- 原 V46 没有保存每次过账的 version，历史保持 NULL；不能用当前头版本伪造历史版本。
ALTER TABLE fg_stock_log ADD COLUMN posting_version INT;

UPDATE fg_stock_log l SET company_id = d.company_id FROM fg_doc d WHERE d.id = l.fg_doc_id;
UPDATE fg_stock s SET company_id = x.company_id, movement_revision = x.revision
FROM (
    SELECT warehouse_id, style_sku_id, location_id, min(company_id) AS company_id, count(*) AS revision
    FROM fg_stock_log GROUP BY warehouse_id, style_sku_id, location_id
) x WHERE (s.warehouse_id, s.style_sku_id, s.location_id) = (x.warehouse_id, x.style_sku_id, x.location_id);
WITH paired AS (
    SELECT l.id, lag(l.id) OVER w AS original_id,
           l.change_qty::bigint * CASE WHEN d.kind = 'IN' THEN 1 ELSE -1 END AS direction
    FROM fg_stock_log l JOIN fg_doc d ON d.id = l.fg_doc_id
    WINDOW w AS (PARTITION BY fg_doc_id, warehouse_id, style_sku_id, location_id ORDER BY l.id)
)
UPDATE fg_stock_log l SET reversal_of_log_id = p.original_id FROM paired p WHERE p.id = l.id AND p.direction < 0;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM fg_stock_log l WHERE l.reversal_of_log_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM fg_stock_log r WHERE r.reversal_of_log_id = l.id)
        GROUP BY l.fg_doc_id,l.company_id,l.warehouse_id,l.style_sku_id,l.location_id HAVING count(*) > 1
    ) OR EXISTS (
        SELECT 1 FROM fg_doc d WHERE (d.status = 'APPROVED') IS DISTINCT FROM EXISTS (
            SELECT 1 FROM fg_stock_log l WHERE l.fg_doc_id = d.id AND l.reversal_of_log_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM fg_stock_log r WHERE r.reversal_of_log_id = l.id)
        )
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: document state and unmatched ledger disagree';
    END IF;
    IF EXISTS (
        WITH expected AS (
            SELECT d.id AS doc_id,d.company_id,COALESCE(d.to_warehouse_id,d.from_warehouse_id) AS warehouse_id,
                   i.style_sku_id,0::bigint AS location_id,
                   i.qty::bigint * CASE WHEN d.kind='IN' THEN 1 ELSE -1 END AS qty,
                   d.doc_type,d.no,d.doc_date
            FROM fg_doc d JOIN fg_doc_item i ON i.doc_id=d.id WHERE d.status='APPROVED'
        ), active AS (
            SELECT l.* FROM fg_stock_log l JOIN fg_doc d ON d.id=l.fg_doc_id
            WHERE d.status='APPROVED' AND l.reversal_of_log_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM fg_stock_log r WHERE r.reversal_of_log_id=l.id)
        )
        SELECT 1 FROM expected e FULL JOIN active a ON
            (e.doc_id,e.company_id,e.warehouse_id,e.style_sku_id,e.location_id)=
            (a.fg_doc_id,a.company_id,a.warehouse_id,a.style_sku_id,a.location_id)
        WHERE e.doc_id IS NULL OR a.id IS NULL OR e.qty <> a.change_qty
           OR (e.doc_type,e.no,e.doc_date) IS DISTINCT FROM (a.change_type,a.doc_no,a.biz_date)
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: approved document items and active events differ';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fg_doc_item i JOIN fg_doc d ON d.id = i.doc_id
        LEFT JOIN prd_receipt_item r ON r.id = i.prd_receipt_item_id
        LEFT JOIN ord_order_item o ON o.id = i.order_item_id
        WHERE (d.doc_type = 'RECEIPT_IN' AND (i.prd_receipt_item_id IS NULL OR i.order_item_id IS NOT NULL
               OR r.receipt_id <> d.prd_receipt_id OR r.style_sku_id <> i.style_sku_id))
           OR (d.doc_type = 'SHIP_OUT' AND (i.order_item_id IS NULL OR i.prd_receipt_item_id IS NOT NULL
               OR o.order_id <> d.order_id OR o.style_sku_id <> i.style_sku_id))
           OR (d.doc_type NOT IN ('RECEIPT_IN','SHIP_OUT') AND (i.prd_receipt_item_id IS NOT NULL OR i.order_item_id IS NOT NULL))
           OR (d.doc_type <> 'SHIP_OUT' AND (i.price IS NOT NULL OR i.amount IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'FG_MIGRATION_RECONCILIATION_REQUIRED: source item or historical price relation mismatch';
    END IF;
END;
$$;

ALTER TABLE fg_stock ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE fg_stock_log ALTER COLUMN company_id SET NOT NULL;
ALTER TABLE fg_stock ADD CONSTRAINT fk_fg_stock_company FOREIGN KEY (company_id) REFERENCES bas_company(id);
ALTER TABLE fg_stock ADD CONSTRAINT ck_fg_stock_revision CHECK (movement_revision >= 0);
-- 单据明细当前没有库位分配，0 仅为未来模型预留；不能换库位绕过每版过账唯一约束。
ALTER TABLE fg_stock ADD CONSTRAINT ck_fg_stock_location CHECK (location_id = 0);
DROP INDEX uk_fg_stock;
CREATE UNIQUE INDEX uk_fg_stock ON fg_stock(company_id, warehouse_id, style_sku_id, location_id);
CREATE INDEX idx_fg_stock_company_sku ON fg_stock(company_id, style_sku_id, warehouse_id);
-- 旧唯一键以 warehouse_id 开头；主体分账后另补仓库 FK 的反向检查索引。
CREATE INDEX idx_fg_stock_warehouse ON fg_stock(warehouse_id);

-- 复合 FK 将来源主体和流水主体落实为关系约束；引用期间上游不能偷换主体。
CREATE UNIQUE INDEX uk_ord_order_id_company ON ord_order(id, company_id);
CREATE UNIQUE INDEX uk_prd_receipt_id_company ON prd_receipt(id, company_id);
CREATE UNIQUE INDEX uk_fg_doc_id_company ON fg_doc(id, company_id);
ALTER TABLE fg_doc ADD CONSTRAINT fk_fg_doc_receipt_company FOREIGN KEY (prd_receipt_id, company_id) REFERENCES prd_receipt(id, company_id);
ALTER TABLE fg_doc ADD CONSTRAINT fk_fg_doc_order_company FOREIGN KEY (order_id, company_id) REFERENCES ord_order(id, company_id);
ALTER TABLE fg_stock_log ADD CONSTRAINT fk_fg_log_doc_company FOREIGN KEY (fg_doc_id, company_id) REFERENCES fg_doc(id, company_id);
-- FK 检查与来源删除必须用完整前缀索引，单列/颠倒列序的查询索引不能替代。
CREATE INDEX idx_fg_doc_receipt_company ON fg_doc(prd_receipt_id, company_id);
CREATE INDEX idx_fg_doc_order_company ON fg_doc(order_id, company_id);
CREATE INDEX idx_fg_log_doc_company ON fg_stock_log(fg_doc_id, company_id);
ALTER TABLE fg_doc ADD CONSTRAINT ck_fg_doc_type CHECK (
    (kind = 'IN' AND doc_type IN ('RECEIPT_IN','OTHER_IN')) OR (kind = 'OUT' AND doc_type IN ('SHIP_OUT','OTHER_OUT')));
ALTER TABLE fg_doc ADD CONSTRAINT ck_fg_doc_status CHECK (status IN ('DRAFT','APPROVED','VOIDED'));
ALTER TABLE fg_doc ADD CONSTRAINT ck_fg_doc_version CHECK (version >= 0);
ALTER TABLE fg_doc_item ADD CONSTRAINT ck_fg_item_seq CHECK (seq > 0);
ALTER TABLE fg_doc_item ADD CONSTRAINT ck_fg_item_price CHECK (price IS NULL OR (price >= 0 AND price::text NOT IN ('NaN','Infinity','-Infinity')));
ALTER TABLE fg_doc_item ADD CONSTRAINT ck_fg_item_amount CHECK (amount IS NULL OR (amount >= 0 AND amount::text NOT IN ('NaN','Infinity','-Infinity')));
ALTER TABLE fg_doc_item ADD CONSTRAINT ck_fg_item_amount_pair CHECK ((price IS NULL) = (amount IS NULL));
ALTER TABLE fg_stock_log ADD CONSTRAINT ck_fg_log_quantity CHECK (change_qty <> 0 AND balance_qty >= 0 AND location_id = 0);
ALTER TABLE fg_stock_log ADD CONSTRAINT ck_fg_log_reversal_self CHECK (reversal_of_log_id IS NULL OR reversal_of_log_id <> id);
ALTER TABLE fg_stock_log ADD CONSTRAINT ck_fg_log_posting_version CHECK (posting_version IS NULL OR posting_version > 0);
CREATE UNIQUE INDEX uk_fg_log_posting_version ON fg_stock_log(fg_doc_id,posting_version,company_id,warehouse_id,style_sku_id,location_id)
    WHERE reversal_of_log_id IS NULL;
CREATE UNIQUE INDEX uk_fg_log_reversal ON fg_stock_log(reversal_of_log_id) WHERE reversal_of_log_id IS NOT NULL;
CREATE INDEX idx_fg_log_company_seek ON fg_stock_log(company_id, create_time DESC, id DESC);
CREATE INDEX idx_fg_log_company_sku_seek ON fg_stock_log(company_id, style_sku_id, create_time DESC, id DESC);
CREATE INDEX idx_fg_doc_receipt_preview ON fg_doc(company_id,prd_receipt_id,create_time DESC,id DESC) WHERE status <> 'VOIDED';
CREATE INDEX idx_fg_doc_company_kind_status ON fg_doc(company_id, kind, status, id DESC);
CREATE INDEX idx_fg_doc_company_no ON fg_doc(company_id, upper(no));
CREATE INDEX idx_fg_log_company_no_seek ON fg_stock_log(company_id, upper(doc_no), create_time DESC, id DESC);
CREATE INDEX idx_fg_doc_no_search ON fg_doc USING gin(upper(no) gin_trgm_ops);
CREATE INDEX idx_fg_doc_logistics_search ON fg_doc USING gin(upper(logistics_no) gin_trgm_ops);
CREATE INDEX idx_fg_log_doc_no_search ON fg_stock_log USING gin(upper(doc_no) gin_trgm_ops);

-- 写入数据库的明细也必须指向这张来源单、同一 SKU；不让错误引用混入后续累计。
CREATE OR REPLACE FUNCTION scm_fg_item_source_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE doc fg_doc%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' AND NEW.doc_id IS DISTINCT FROM OLD.doc_id THEN
        RAISE EXCEPTION 'FG_ITEM_REPARENT_FORBIDDEN' USING ERRCODE='23514';
    END IF;
    -- 正常领域动作先持有头锁。直接 SQL 已先锁 item 时不能等待头锁造成反序死锁，冲突即拒绝。
    BEGIN
        SELECT * INTO STRICT doc FROM fg_doc WHERE id = CASE WHEN TG_OP='DELETE' THEN OLD.doc_id ELSE NEW.doc_id END FOR UPDATE NOWAIT;
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION 'FG_DOCUMENT_IS_BEING_CHANGED_RETRY' USING ERRCODE='23514';
    END;
    IF doc.status <> 'DRAFT' THEN RAISE EXCEPTION 'FG_ONLY_DRAFT_ITEMS_EDITABLE' USING ERRCODE = '23514'; END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF doc.doc_type = 'RECEIPT_IN' THEN
        IF NEW.order_item_id IS NOT NULL OR NEW.prd_receipt_item_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM prd_receipt_item i WHERE i.id = NEW.prd_receipt_item_id
              AND i.receipt_id = doc.prd_receipt_id AND i.style_sku_id = NEW.style_sku_id
        ) THEN RAISE EXCEPTION 'FG_ITEM_SOURCE_MISMATCH' USING ERRCODE = '23514'; END IF;
    ELSIF doc.doc_type = 'SHIP_OUT' THEN
        IF NEW.prd_receipt_item_id IS NOT NULL OR NEW.order_item_id IS NULL OR NOT EXISTS (
            SELECT 1 FROM ord_order_item i WHERE i.id = NEW.order_item_id
              AND i.order_id = doc.order_id AND i.style_sku_id = NEW.style_sku_id
        ) THEN RAISE EXCEPTION 'FG_ITEM_SOURCE_MISMATCH' USING ERRCODE = '23514'; END IF;
    ELSIF NEW.prd_receipt_item_id IS NOT NULL OR NEW.order_item_id IS NOT NULL THEN
        RAISE EXCEPTION 'FG_ITEM_SOURCE_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF doc.doc_type <> 'SHIP_OUT' AND (NEW.price IS NOT NULL OR NEW.amount IS NOT NULL) THEN
        RAISE EXCEPTION 'FG_NON_SHIPPING_PRICE_MUST_BE_NULL' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER fg_item_source_guard BEFORE INSERT OR UPDATE OR DELETE ON fg_doc_item FOR EACH ROW EXECUTE FUNCTION scm_fg_item_source_guard();

CREATE OR REPLACE FUNCTION scm_fg_ledger_insert_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE original fg_stock_log%ROWTYPE; doc fg_doc%ROWTYPE;
BEGIN
    IF NEW.location_id <> 0 THEN
        RAISE EXCEPTION 'FG_UNSUPPORTED_LOCATION' USING ERRCODE='23514';
    END IF;
    BEGIN
        SELECT * INTO STRICT doc FROM fg_doc WHERE id=NEW.fg_doc_id FOR UPDATE NOWAIT;
    EXCEPTION WHEN lock_not_available THEN
        RAISE EXCEPTION 'FG_DOCUMENT_IS_BEING_CHANGED_RETRY' USING ERRCODE='23514';
    END;
    IF NEW.company_id <> doc.company_id OR NEW.change_type <> doc.doc_type OR NEW.doc_no <> doc.no THEN
        RAISE EXCEPTION 'FG_LEDGER_DOCUMENT_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF NEW.reversal_of_log_id IS NOT NULL THEN
        SELECT * INTO STRICT original FROM fg_stock_log WHERE id = NEW.reversal_of_log_id;
        IF original.reversal_of_log_id IS NOT NULL OR
           (NEW.fg_doc_id,NEW.company_id,NEW.change_type,NEW.doc_no,NEW.biz_date,NEW.warehouse_id,NEW.style_sku_id,NEW.location_id)
           IS DISTINCT FROM
           (original.fg_doc_id,original.company_id,original.change_type,original.doc_no,original.biz_date,original.warehouse_id,original.style_sku_id,original.location_id)
           OR NEW.change_qty <> -original.change_qty THEN
            RAISE EXCEPTION 'FG_LEDGER_REVERSAL_MISMATCH' USING ERRCODE = '23514';
        END IF;
    ELSIF (doc.kind = 'IN' AND NEW.change_qty < 0) OR (doc.kind = 'OUT' AND NEW.change_qty > 0) THEN
        RAISE EXCEPTION 'FG_LEDGER_DIRECTION_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF NEW.reversal_of_log_id IS NULL THEN
        IF doc.status <> 'APPROVED' OR NEW.warehouse_id <> COALESCE(doc.to_warehouse_id,doc.from_warehouse_id)
           OR NEW.biz_date <> doc.doc_date THEN
            RAISE EXCEPTION 'FG_LEDGER_POSTING_STATE_MISMATCH' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM fg_doc_item i WHERE i.doc_id=NEW.fg_doc_id
            AND i.style_sku_id=NEW.style_sku_id AND i.qty::bigint=abs(NEW.change_qty::bigint)) THEN
            RAISE EXCEPTION 'FG_LEDGER_ITEM_MISMATCH' USING ERRCODE='23514';
        END IF;
        NEW.posting_version := doc.version;
    ELSIF doc.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'FG_LEDGER_REVERSAL_STATE_MISMATCH' USING ERRCODE = '23514';
    ELSE
        NEW.posting_version := original.posting_version;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fg_stock s WHERE
        (s.company_id,s.warehouse_id,s.style_sku_id,s.location_id,s.qty) =
        (NEW.company_id,NEW.warehouse_id,NEW.style_sku_id,NEW.location_id,NEW.balance_qty)) THEN
        RAISE EXCEPTION 'FG_LEDGER_BALANCE_MISMATCH' USING ERRCODE = '23514';
    END IF;
    NEW.request_id := NULLIF(current_setting('scm.audit_request',true),'');
    RETURN NEW;
END;
$$;
CREATE TRIGGER fg_ledger_insert_guard BEFORE INSERT ON fg_stock_log FOR EACH ROW EXECUTE FUNCTION scm_fg_ledger_insert_guard();

CREATE OR REPLACE FUNCTION scm_fg_ledger_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '成品库存流水只允许追加，不允许修改、删除或清空' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER fg_ledger_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON fg_stock_log
    FOR EACH STATEMENT EXECUTE FUNCTION scm_fg_ledger_append_only();
