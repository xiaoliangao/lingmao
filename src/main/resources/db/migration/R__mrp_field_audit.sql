-- 只追加算料头的白名单审计，不修改已发布迁移，不批量猜补历史 company_id。
-- Flyway 默认单事务安装；只取得有界元数据锁，不扫描或重写历史算料单。
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

CREATE OR REPLACE FUNCTION scm_mrp_audit_projection(source_row JSONB)
RETURNS JSONB LANGUAGE SQL IMMUTABLE SET search_path = pg_catalog, public AS $$
    SELECT COALESCE(jsonb_object_agg(field.key,
        CASE WHEN (field.key = 'id' OR right(field.key, 3) IN ('_id', '_by')) AND field.value <> 'null'::jsonb
             THEN to_jsonb(field.value #>> '{}') ELSE field.value END), '{}'::jsonb)
    FROM jsonb_each(COALESCE(source_row, '{}'::jsonb)) field
    WHERE field.key = ANY(ARRAY['id','no','company_id','need_date','prev_calc_id','status','close_reason',
        'close_by','close_time','audit_by','audit_time','version'])
$$;

CREATE OR REPLACE FUNCTION scm_capture_mrp_field_diff()
RETURNS TRIGGER LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP = 'INSERT' THEN '{}'::jsonb ELSE scm_mrp_audit_projection(to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN '{}'::jsonb ELSE scm_mrp_audit_projection(to_jsonb(NEW)) END;
    actual_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN before_row ELSE after_row END;
    changes JSONB;
    row_identity BIGINT := (actual_row->>'id')::bigint;
    namespace TEXT;
    actor TEXT := NULLIF(current_setting('scm.audit_actor', true), '');
BEGIN
    changes := scm_audit_diff(before_row, after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;
    SELECT tenant_id INTO namespace FROM sys_deployment_namespace WHERE singleton;
    INSERT INTO sys_data_log(tenant_key, table_name, row_id, root_table, root_id,
                             price_domain, action, diff, actor_user_id, client_ip, request_id, operation, transaction_id)
    VALUES(COALESCE(namespace, 'uninitialized'), TG_TABLE_NAME, row_identity, TG_TABLE_NAME, row_identity,
           'COST', TG_OP, changes,
           CASE WHEN actor ~ '^[1-9][0-9]{0,18}$'
                     AND (length(actor) < 19 OR actor COLLATE "C" <= '9223372036854775807' COLLATE "C")
                THEN actor::bigint ELSE NULL END,
           NULLIF(current_setting('scm.audit_ip', true), ''),
           NULLIF(current_setting('scm.audit_request', true), ''),
           NULLIF(current_setting('scm.audit_operation', true), ''), txid_current());
    RETURN NULL;
END $$;

CREATE OR REPLACE TRIGGER mrp_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON mrp_calc
    FOR EACH ROW EXECUTE FUNCTION scm_capture_mrp_field_diff();

COMMENT ON COLUMN mrp_calc.company_id IS '由完整真实来源订单证明的主体；旧空值保留，成功重算时按版本回填';
