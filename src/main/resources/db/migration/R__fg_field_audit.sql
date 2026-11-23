-- 成品字段审计只追加独立触发器，不改已发布 V36/V46，也不重复写 fg_stock_log。
-- Flyway 默认单事务安装三个触发器；锁等待/语句均有上限，无历史回填或全表扫描。
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

-- 先按业务白名单物理投影再求 Diff；送货地址、物流号、备注、自由原因和后续新增字段不入库。
-- 原始行和 Diff 中的 ID 均为十进制字符串；单价/金额仍为精确 NUMERIC JSON 值。
CREATE OR REPLACE FUNCTION scm_fg_audit_projection(target_table TEXT, source_row JSONB)
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(jsonb_object_agg(field.key,
        CASE WHEN (field.key = 'id' OR right(field.key, 3) IN ('_id', '_by')) AND field.value <> 'null'::jsonb
             THEN to_jsonb(field.value #>> '{}') ELSE field.value END), '{}'::jsonb)
    FROM jsonb_each(COALESCE(source_row, '{}'::jsonb)) field
    WHERE field.key = ANY(CASE target_table
        WHEN 'fg_doc' THEN ARRAY['id','no','company_id','kind','doc_type','status','from_warehouse_id',
            'to_warehouse_id','prd_receipt_id','order_id','doc_date','audit_by','audit_time','version']
        WHEN 'fg_doc_item' THEN ARRAY['id','doc_id','seq','style_sku_id','qty','prd_receipt_item_id',
            'order_item_id','price','amount']
        WHEN 'fg_stock' THEN ARRAY['id','company_id','warehouse_id','style_sku_id','location_id','qty','movement_revision']
        ELSE ARRAY[]::text[] END)
$$;

CREATE OR REPLACE FUNCTION scm_capture_fg_field_diff()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP = 'INSERT' THEN '{}'::jsonb
                            ELSE scm_fg_audit_projection(TG_TABLE_NAME, to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN '{}'::jsonb
                           ELSE scm_fg_audit_projection(TG_TABLE_NAME, to_jsonb(NEW)) END;
    actual_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN before_row ELSE after_row END;
    changes JSONB;
    row_identity BIGINT := (actual_row->>'id')::bigint;
    root_table_name TEXT := TG_TABLE_NAME;
    root_identity BIGINT := row_identity;
    namespace TEXT;
    actor TEXT := NULLIF(current_setting('scm.audit_actor', true), '');
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'fg_doc', 'fg_stock' THEN NULL;
        WHEN 'fg_doc_item' THEN
            root_table_name := 'fg_doc';
            root_identity := (actual_row->>'doc_id')::bigint;
        ELSE
            RAISE EXCEPTION 'Unsupported finished-goods audit table' USING ERRCODE = '55000';
    END CASE;

    changes := scm_audit_diff(before_row, after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;

    SELECT tenant_id INTO namespace FROM sys_deployment_namespace WHERE singleton;
    INSERT INTO sys_data_log(tenant_key, table_name, row_id, root_table, root_id,
                             price_domain, action, diff, actor_user_id, client_ip, request_id, operation, transaction_id)
    VALUES(COALESCE(namespace, 'uninitialized'), TG_TABLE_NAME, row_identity, root_table_name, root_identity,
           'ORDER', TG_OP, changes,
           CASE WHEN actor ~ '^[1-9][0-9]{0,18}$'
                     AND (length(actor) < 19 OR actor COLLATE "C" <= '9223372036854775807' COLLATE "C")
                THEN actor::bigint ELSE NULL END,
           NULLIF(current_setting('scm.audit_ip', true), ''),
           NULLIF(current_setting('scm.audit_request', true), ''),
           NULLIF(current_setting('scm.audit_operation', true), ''), txid_current());
    RETURN NULL;
END $$;

CREATE OR REPLACE TRIGGER fg_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON fg_doc
    FOR EACH ROW EXECUTE FUNCTION scm_capture_fg_field_diff();
CREATE OR REPLACE TRIGGER fg_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON fg_doc_item
    FOR EACH ROW EXECUTE FUNCTION scm_capture_fg_field_diff();
CREATE OR REPLACE TRIGGER fg_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON fg_stock
    FOR EACH ROW EXECUTE FUNCTION scm_capture_fg_field_diff();
