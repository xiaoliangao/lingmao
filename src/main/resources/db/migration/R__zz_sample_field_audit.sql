-- 在 R__security_field_audit 之后扩展真实行身份；不改 V36/V58/V59 或旧 repeatable。
-- 第一事务只安装元数据/NOT VALID CHECK/触发器；第二事务验证旧行，释放元数据排他锁后再扫描。
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

-- CHECK 只验证纯值。真实 namespace/不可重绑父链由下面的 BEFORE INSERT 守卫验证，
-- 不在 CHECK 中跨表查询，避免约束验证和备份恢复依赖可变的当前业务状态。
CREATE OR REPLACE FUNCTION scm_sample_audit_identity_valid(target_tenant TEXT, target_table TEXT,
    row_identity BIGINT, identity_key JSONB, target_root TEXT, root_identity BIGINT)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(target_root = 'dev_sample' AND root_identity > 0
        AND target_tenant IS NOT NULL AND length(target_tenant) BETWEEN 1 AND 64
        AND CASE
            WHEN target_table IN ('dev_sample','dev_sample_order','dev_sample_node','dev_sample_review') THEN
                row_identity > 0 AND identity_key IS NULL
                AND (target_table <> 'dev_sample' OR row_identity = root_identity)
            WHEN target_table IN ('dev_sample_color','dev_sample_order_color','dev_sample_order_size','dev_sample_reviewer') THEN
                row_identity IS NULL AND jsonb_typeof(identity_key) = 'object'
                AND jsonb_typeof(identity_key->'tenant_key') = 'string'
                AND identity_key->>'tenant_key' = target_tenant
                AND scm_security_audit_id_valid(identity_key->'company_id')
                AND CASE target_table
                    WHEN 'dev_sample_color' THEN
                        identity_key ?& ARRAY['tenant_key','company_id','sample_id','color_id']
                        AND identity_key - ARRAY['tenant_key','company_id','sample_id','color_id'] = '{}'::jsonb
                        AND scm_security_audit_id_valid(identity_key->'sample_id')
                        AND scm_security_audit_id_valid(identity_key->'color_id')
                        AND identity_key->>'sample_id' = root_identity::text
                    WHEN 'dev_sample_order_color' THEN
                        identity_key ?& ARRAY['tenant_key','company_id','sample_order_id','color_id']
                        AND identity_key - ARRAY['tenant_key','company_id','sample_order_id','color_id'] = '{}'::jsonb
                        AND scm_security_audit_id_valid(identity_key->'sample_order_id')
                        AND scm_security_audit_id_valid(identity_key->'color_id')
                    WHEN 'dev_sample_order_size' THEN
                        identity_key ?& ARRAY['tenant_key','company_id','sample_order_id','size_code']
                        AND identity_key - ARRAY['tenant_key','company_id','sample_order_id','size_code'] = '{}'::jsonb
                        AND scm_security_audit_id_valid(identity_key->'sample_order_id')
                        AND jsonb_typeof(identity_key->'size_code') = 'string'
                        AND length(identity_key->>'size_code') <= 16
                    WHEN 'dev_sample_reviewer' THEN
                        identity_key ?& ARRAY['tenant_key','company_id','review_id','user_id']
                        AND identity_key - ARRAY['tenant_key','company_id','review_id','user_id'] = '{}'::jsonb
                        AND scm_security_audit_id_valid(identity_key->'review_id')
                        AND scm_security_audit_id_valid(identity_key->'user_id')
                    ELSE false
                END
            ELSE false
        END, false)
$$;

-- pg_restore 用空 search_path 装载数据，CHECK 中两个 SQL wrapper 的内部 helper
-- 不能依赖调用连接的搜索路径。只固定纯值函数自身的路径；不改变恢复会话、CHECK 或历史迁移。
ALTER FUNCTION public.scm_security_audit_link_valid(TEXT, JSONB, TEXT, BIGINT)
    SET search_path = pg_catalog, public;
ALTER FUNCTION public.scm_sample_audit_identity_valid(TEXT, TEXT, BIGINT, JSONB, TEXT, BIGINT)
    SET search_path = pg_catalog, public;

-- 保留原 security 两种关联规则和其余旧表的非空 row_id 规则，不能借样衣扩展放松旧身份。
ALTER TABLE sys_data_log DROP CONSTRAINT ck_data_log_row_identity;
ALTER TABLE sys_data_log ADD CONSTRAINT ck_data_log_row_identity CHECK (
    CASE
        WHEN table_name IN ('sys_user_role','sys_role_menu') THEN
            row_id IS NULL AND price_domain = 'SECURITY'
            AND scm_security_audit_link_valid(table_name, row_key, root_table, root_id)
        WHEN table_name IN ('dev_sample','dev_sample_order','dev_sample_node','dev_sample_review',
                            'dev_sample_color','dev_sample_order_color','dev_sample_order_size','dev_sample_reviewer') THEN
            price_domain = 'COST'
            AND scm_sample_audit_identity_valid(tenant_key, table_name, row_id, row_key, root_table, root_id)
        ELSE row_id IS NOT NULL AND row_key IS NULL
    END
) NOT VALID;

-- 只处理八张样衣表的事件。头、单、节点和评审不可物理删除，所属样衣不可重挂；
-- 因此即使颜色/尺码行已受控删除，仍可沿保留的真实父头验证唯一业务根。
-- company_id 是当时真实复合键的一部分；合法草稿换公司后，旧键不能被当前公司覆盖。
CREATE OR REPLACE FUNCTION scm_guard_sample_audit_identity()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    namespace TEXT;
    actual_root BIGINT;
BEGIN
    IF NEW.table_name NOT IN ('dev_sample','dev_sample_order','dev_sample_node','dev_sample_review',
                             'dev_sample_color','dev_sample_order_color','dev_sample_order_size','dev_sample_reviewer') THEN
        RETURN NEW;
    END IF;
    SELECT tenant_id INTO namespace FROM sys_deployment_namespace WHERE singleton;
    IF NEW.tenant_key IS DISTINCT FROM namespace OR NEW.price_domain <> 'COST'
       OR NOT scm_sample_audit_identity_valid(NEW.tenant_key, NEW.table_name, NEW.row_id, NEW.row_key, NEW.root_table, NEW.root_id) THEN
        RAISE EXCEPTION 'Invalid sample audit identity' USING ERRCODE = '23514';
    END IF;
    CASE NEW.table_name
        WHEN 'dev_sample' THEN
            SELECT id INTO actual_root FROM dev_sample WHERE tenant_key=namespace AND id=NEW.row_id;
        WHEN 'dev_sample_order' THEN
            SELECT sample_id INTO actual_root FROM dev_sample_order WHERE tenant_key=namespace AND id=NEW.row_id;
        WHEN 'dev_sample_node' THEN
            SELECT o.sample_id INTO actual_root FROM dev_sample_node n
                JOIN dev_sample_order o ON o.tenant_key=n.tenant_key AND o.id=n.sample_order_id
                WHERE n.tenant_key=namespace AND n.id=NEW.row_id;
        WHEN 'dev_sample_review' THEN
            SELECT o.sample_id INTO actual_root FROM dev_sample_review r
                JOIN dev_sample_order o ON o.tenant_key=r.tenant_key AND o.id=r.sample_order_id
                WHERE r.tenant_key=namespace AND r.id=NEW.row_id;
        WHEN 'dev_sample_color' THEN
            SELECT id INTO actual_root FROM dev_sample
                WHERE tenant_key=namespace AND id=(NEW.row_key->>'sample_id')::bigint;
        WHEN 'dev_sample_order_color', 'dev_sample_order_size' THEN
            SELECT sample_id INTO actual_root FROM dev_sample_order
                WHERE tenant_key=namespace AND id=(NEW.row_key->>'sample_order_id')::bigint;
        WHEN 'dev_sample_reviewer' THEN
            SELECT o.sample_id INTO actual_root FROM dev_sample_review r
                JOIN dev_sample_order o ON o.tenant_key=r.tenant_key AND o.id=r.sample_order_id
                WHERE r.tenant_key=namespace AND r.id=(NEW.row_key->>'review_id')::bigint;
    END CASE;
    IF actual_root IS NULL OR actual_root IS DISTINCT FROM NEW.root_id THEN
        RAISE EXCEPTION 'Invalid sample audit root' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE TRIGGER sample_audit_identity_guard
    BEFORE INSERT ON sys_data_log FOR EACH ROW EXECUTE FUNCTION scm_guard_sample_audit_identity();

-- 先对白名单做物理投影，再求 Diff。自由备注、评审意见、要求、面料描述和客户款号不入库；
-- 后续新增字段也不会自动进入审计。估计成本受 COST 读权，公开吊牌价不按成本隐藏。
CREATE OR REPLACE FUNCTION scm_sample_audit_projection(target_table TEXT, source_row JSONB)
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(jsonb_object_agg(field.key,
        CASE WHEN (field.key IN ('id','deleted_token') OR right(field.key, 3) IN ('_id','_by'))
                       AND field.value <> 'null'::jsonb
             THEN to_jsonb(field.value #>> '{}') ELSE field.value END), '{}'::jsonb)
    FROM jsonb_each(COALESCE(source_row, '{}'::jsonb)) field
    WHERE field.key = ANY(CASE target_table
        WHEN 'dev_sample' THEN ARRAY['id','tenant_key','company_id','code','name','brand','category_id','year','season','wave',
            'designer_id','pattern_maker_id','source','customer_id','size_group_id','sample_factory_id','fabric_supplier_id',
            'expect_finish_date','est_tag_price','est_cost','status','converted_style_id','convert_by','convert_time',
            'version','deleted_token','create_by','update_by']
        WHEN 'dev_sample_color' THEN ARRAY['tenant_key','company_id','sample_id','color_id','seq']
        WHEN 'dev_sample_order' THEN ARRAY['id','tenant_key','company_id','no','sample_id','sample_version','sample_type',
            'make_way','factory_id','maker_id','need_date','qty','size_group_id','status','version','dispatched_by',
            'dispatched_time','create_by','update_by']
        WHEN 'dev_sample_order_color' THEN ARRAY['tenant_key','company_id','sample_order_id','color_id','seq','color_code','color_name','color_hex']
        WHEN 'dev_sample_order_size' THEN ARRAY['tenant_key','company_id','sample_order_id','size_group_id','seq','size_code']
        WHEN 'dev_sample_node' THEN ARRAY['id','tenant_key','company_id','sample_order_id','seq','node_code','node_name',
            'duration_days','plan_date','done_date','done_by','owner_id','update_by']
        WHEN 'dev_sample_review' THEN ARRAY['id','tenant_key','company_id','sample_order_id','review_date','result','create_by','update_by']
        WHEN 'dev_sample_reviewer' THEN ARRAY['tenant_key','company_id','review_id','user_id','seq']
        ELSE ARRAY[]::text[] END)
$$;

CREATE OR REPLACE FUNCTION scm_capture_sample_field_diff()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb
                            ELSE scm_sample_audit_projection(TG_TABLE_NAME, to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb
                           ELSE scm_sample_audit_projection(TG_TABLE_NAME, to_jsonb(NEW)) END;
    actual_row JSONB := CASE WHEN TG_OP='DELETE' THEN before_row ELSE after_row END;
    changes JSONB;
    row_identity BIGINT;
    identity_key JSONB;
    root_identity BIGINT;
    namespace TEXT := actual_row->>'tenant_key';
    actor TEXT := NULLIF(current_setting('scm.audit_actor', true), '');
BEGIN
    changes := scm_audit_diff(before_row, after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;
    CASE TG_TABLE_NAME
        WHEN 'dev_sample' THEN
            row_identity := (actual_row->>'id')::bigint;
            root_identity := row_identity;
        WHEN 'dev_sample_order' THEN
            row_identity := (actual_row->>'id')::bigint;
            root_identity := (actual_row->>'sample_id')::bigint;
        WHEN 'dev_sample_node', 'dev_sample_review' THEN
            row_identity := (actual_row->>'id')::bigint;
            SELECT sample_id INTO root_identity FROM dev_sample_order
                WHERE tenant_key=namespace AND id=(actual_row->>'sample_order_id')::bigint;
        WHEN 'dev_sample_color' THEN
            identity_key := jsonb_build_object('tenant_key', namespace, 'company_id', actual_row->>'company_id',
                                               'sample_id', actual_row->>'sample_id', 'color_id', actual_row->>'color_id');
            root_identity := (actual_row->>'sample_id')::bigint;
        WHEN 'dev_sample_order_color' THEN
            identity_key := jsonb_build_object('tenant_key', namespace, 'company_id', actual_row->>'company_id',
                                               'sample_order_id', actual_row->>'sample_order_id', 'color_id', actual_row->>'color_id');
            SELECT sample_id INTO root_identity FROM dev_sample_order
                WHERE tenant_key=namespace AND id=(actual_row->>'sample_order_id')::bigint;
        WHEN 'dev_sample_order_size' THEN
            identity_key := jsonb_build_object('tenant_key', namespace, 'company_id', actual_row->>'company_id',
                                               'sample_order_id', actual_row->>'sample_order_id', 'size_code', actual_row->>'size_code');
            SELECT sample_id INTO root_identity FROM dev_sample_order
                WHERE tenant_key=namespace AND id=(actual_row->>'sample_order_id')::bigint;
        WHEN 'dev_sample_reviewer' THEN
            identity_key := jsonb_build_object('tenant_key', namespace, 'company_id', actual_row->>'company_id',
                                               'review_id', actual_row->>'review_id', 'user_id', actual_row->>'user_id');
            SELECT o.sample_id INTO root_identity FROM dev_sample_review r
                JOIN dev_sample_order o ON o.tenant_key=r.tenant_key AND o.id=r.sample_order_id
                WHERE r.tenant_key=namespace AND r.id=(actual_row->>'review_id')::bigint;
        ELSE
            RAISE EXCEPTION 'Unsupported sample audit table' USING ERRCODE = '55000';
    END CASE;
    INSERT INTO sys_data_log(tenant_key,table_name,row_id,row_key,root_table,root_id,price_domain,action,diff,
                             actor_user_id,client_ip,request_id,operation,transaction_id)
    VALUES(namespace,TG_TABLE_NAME,row_identity,identity_key,'dev_sample',root_identity,'COST',TG_OP,changes,
           CASE WHEN scm_security_audit_id_valid(to_jsonb(actor)) THEN actor::bigint ELSE NULL END,
           NULLIF(current_setting('scm.audit_ip', true), ''), NULLIF(current_setting('scm.audit_request', true), ''),
           NULLIF(current_setting('scm.audit_operation', true), ''), txid_current());
    RETURN NULL;
END $$;

CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_color
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_order
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_order_color
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_order_size
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_node
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_review
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
CREATE OR REPLACE TRIGGER sample_field_audit AFTER INSERT OR UPDATE OR DELETE ON dev_sample_reviewer
    FOR EACH ROW EXECUTE FUNCTION scm_capture_sample_field_diff();
COMMIT;

BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE sys_data_log VALIDATE CONSTRAINT ck_data_log_row_identity;
COMMIT;
