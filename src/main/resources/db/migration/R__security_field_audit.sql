-- 权限变更必须和业务修改一起留下字段级事实，用户名、密码等私密字段不进入审计表。
-- 使用真实联合键表示关联事件；不把用户/角色 ID 冒充关联行 ID，也不为关联表制造代理 ID。
-- Repeatable 只扩展现有审计契约，不回改 V36。配套 .conf 禁用 Flyway 的整文件事务。
-- 第一事务只安装元数据、NOT VALID 守卫和触发器；提交后才验证旧行，避免全表验证时持有 AccessExclusive。
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

ALTER TABLE sys_data_log ADD COLUMN IF NOT EXISTS row_key JSONB;
ALTER TABLE sys_data_log ALTER COLUMN row_id DROP NOT NULL;
COMMENT ON COLUMN sys_data_log.row_key IS '关联审计的真实联合键，所有 ID 为字符串；实体行使用 row_id 且此列为空';

-- 不使用 bigint 强转来验证任意 JSON：超长数值、负数、小数、JSON null 均返回 false。
CREATE OR REPLACE FUNCTION scm_security_audit_id_valid(value JSONB)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(jsonb_typeof(value) = 'string'
        AND (value #>> '{}') ~ '^[1-9][0-9]{0,18}$'
        AND (length(value #>> '{}') < 19
             OR (value #>> '{}') COLLATE "C" <= '9223372036854775807' COLLATE "C"), false)
$$;

CREATE OR REPLACE FUNCTION scm_security_audit_link_valid(target_table TEXT, identity_key JSONB,
                                                         target_root TEXT, root_identity BIGINT)
RETURNS BOOLEAN LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(CASE
        WHEN jsonb_typeof(identity_key) <> 'object' THEN false
        WHEN target_table = 'sys_user_role' THEN
            target_root = 'sys_user'
            AND identity_key ?& ARRAY['user_id','role_id']
            AND identity_key - ARRAY['user_id','role_id'] = '{}'::jsonb
            AND scm_security_audit_id_valid(identity_key->'user_id')
            AND scm_security_audit_id_valid(identity_key->'role_id')
            AND root_identity::text = identity_key->>'user_id'
        WHEN target_table = 'sys_role_menu' THEN
            target_root = 'sys_role'
            AND identity_key ?& ARRAY['role_id','menu_id']
            AND identity_key - ARRAY['role_id','menu_id'] = '{}'::jsonb
            AND scm_security_audit_id_valid(identity_key->'role_id')
            AND scm_security_audit_id_valid(identity_key->'menu_id')
            AND root_identity::text = identity_key->>'role_id'
        ELSE false
    END, false)
$$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'sys_data_log'::regclass AND conname = 'ck_data_log_row_identity') THEN
        ALTER TABLE sys_data_log ADD CONSTRAINT ck_data_log_row_identity CHECK (
            CASE WHEN table_name IN ('sys_user_role','sys_role_menu') THEN
                row_id IS NULL AND price_domain = 'SECURITY'
                AND scm_security_audit_link_valid(table_name, row_key, root_table, root_id)
            ELSE row_id IS NOT NULL AND row_key IS NULL END
        ) NOT VALID;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'sys_data_log'::regclass AND conname = 'ck_data_log_price_domain_security') THEN
        ALTER TABLE sys_data_log ADD CONSTRAINT ck_data_log_price_domain_security
            CHECK (price_domain IN ('COST','ORDER','PRODUCTION','SECURITY')) NOT VALID;
    END IF;
    ALTER TABLE sys_data_log DROP CONSTRAINT IF EXISTS sys_data_log_price_domain_check;
END $$;

-- 先对白名单做物理投影，再求 diff。新增 sys_user/sys_role 字段不会自动进入日志。
-- 关联 ID 与实体 ID 都转字符串；session_version 是计数器，保持数值类型。
CREATE OR REPLACE FUNCTION scm_security_audit_projection(target_table TEXT, source_row JSONB)
RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT COALESCE(jsonb_object_agg(field.key,
        CASE WHEN (field.key = 'id' OR right(field.key, 3) = '_id') AND field.value <> 'null'::jsonb
             THEN to_jsonb(field.value #>> '{}') ELSE field.value END), '{}'::jsonb)
    FROM jsonb_each(COALESCE(source_row, '{}'::jsonb)) field
    WHERE field.key = ANY(CASE target_table
        WHEN 'sys_user' THEN ARRAY['id','status','deleted','default_company_id','session_version','designer_no_locked']
        WHEN 'sys_role' THEN ARRAY['id','code','status','deleted','sort','data_scope']
        WHEN 'sys_user_role' THEN ARRAY['user_id','role_id']
        WHEN 'sys_role_menu' THEN ARRAY['role_id','menu_id']
        ELSE ARRAY[]::text[] END)
$$;

CREATE OR REPLACE FUNCTION scm_capture_security_field_diff()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP = 'INSERT' THEN '{}'::jsonb
                            ELSE scm_security_audit_projection(TG_TABLE_NAME, to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN '{}'::jsonb
                           ELSE scm_security_audit_projection(TG_TABLE_NAME, to_jsonb(NEW)) END;
    actual_row JSONB := CASE WHEN TG_OP = 'DELETE' THEN before_row ELSE after_row END;
    changes JSONB;
    identity_key JSONB;
    row_identity BIGINT;
    root_table_name TEXT := TG_TABLE_NAME;
    root_identity BIGINT;
    namespace TEXT;
    actor TEXT := NULLIF(current_setting('scm.audit_actor', true), '');
BEGIN
    changes := scm_audit_diff(before_row, after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;

    CASE TG_TABLE_NAME
        WHEN 'sys_user', 'sys_role' THEN
            row_identity := (actual_row->>'id')::bigint;
            root_identity := row_identity;
        WHEN 'sys_user_role' THEN
            identity_key := actual_row;
            root_table_name := 'sys_user';
            root_identity := (actual_row->>'user_id')::bigint;
        WHEN 'sys_role_menu' THEN
            identity_key := actual_row;
            root_table_name := 'sys_role';
            root_identity := (actual_row->>'role_id')::bigint;
        ELSE
            RAISE EXCEPTION 'Unsupported security audit table' USING ERRCODE = '55000';
    END CASE;

    SELECT tenant_id INTO namespace FROM sys_deployment_namespace WHERE singleton;
    INSERT INTO sys_data_log(tenant_key, table_name, row_id, row_key, root_table, root_id,
                             price_domain, action, diff, actor_user_id, client_ip, request_id, operation, transaction_id)
    VALUES(COALESCE(namespace, 'uninitialized'), TG_TABLE_NAME, row_identity, identity_key,
           root_table_name, root_identity, 'SECURITY', TG_OP, changes,
           CASE WHEN scm_security_audit_id_valid(to_jsonb(actor)) THEN actor::bigint ELSE NULL END,
           NULLIF(current_setting('scm.audit_ip', true), ''),
           NULLIF(current_setting('scm.audit_request', true), ''),
           NULLIF(current_setting('scm.audit_operation', true), ''), txid_current());
    RETURN NULL;
END $$;

CREATE OR REPLACE TRIGGER security_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION scm_capture_security_field_diff();
CREATE OR REPLACE TRIGGER security_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON sys_role
    FOR EACH ROW EXECUTE FUNCTION scm_capture_security_field_diff();
CREATE OR REPLACE TRIGGER security_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON sys_user_role
    FOR EACH ROW EXECUTE FUNCTION scm_capture_security_field_diff();
CREATE OR REPLACE TRIGGER security_field_audit
    AFTER INSERT OR UPDATE OR DELETE ON sys_role_menu
    FOR EACH ROW EXECUTE FUNCTION scm_capture_security_field_diff();
COMMIT;

-- 此时写入已经受新 CHECK 保护。旧数据验证使用 ShareUpdateExclusive，不阻塞普通读写。
-- 验证失败时安装事务仍然完整有效；下一次 repeatable 幂等重装后继续验证，不留无守卫窗口。
-- 两个显式事务都只用 SET LOCAL，成功提交或失败回滚后不会泄漏会话 timeout 设置。
BEGIN;
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';
ALTER TABLE sys_data_log VALIDATE CONSTRAINT ck_data_log_row_identity;
ALTER TABLE sys_data_log VALIDATE CONSTRAINT ck_data_log_price_domain_security;
COMMIT;
