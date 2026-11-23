-- 公共标签：部署共享字典 + 有权限的真实领域对象关联。
-- V1–V57 不改写。仅新增空表/索引/触发器/菜单，不推断 legacy_attrs/style_tag，
-- 不写业务种子；sys_deployment_namespace 在迁移后由已有启动守卫初始化。
-- 安装父表触发器仍需短暂表锁；在受控升级窗口执行，锁超时即整笔回滚，不宣称零停机。
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE biz_tag (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key    VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    name          VARCHAR(64) NOT NULL,
    canonical     VARCHAR(256) COLLATE "C" NOT NULL,
    version       INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    deleted_token BIGINT NOT NULL DEFAULT 0 CHECK (deleted_token = 0 OR deleted_token = id),
    create_by     BIGINT,
    create_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by     BIGINT,
    update_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_biz_tag_tenant_id UNIQUE (tenant_key, id),
    CONSTRAINT uk_biz_tag_name_token UNIQUE (tenant_key, canonical, deleted_token),
    CONSTRAINT ck_biz_tag_namespace CHECK (tenant_key ~ '^[A-Za-z0-9_-]{1,64}$'),
    CONSTRAINT ck_biz_tag_text CHECK (
        char_length(name) BETWEEN 1 AND 64 AND char_length(canonical) BETWEEN 1 AND 256
        AND name !~ '[[:cntrl:],]' AND canonical !~ '[[:cntrl:],]'
        AND name !~ U&'[\202A-\202E\2066-\2069]'
        AND canonical !~ U&'[\202A-\202E\2066-\2069]')
);
-- C 排序同时服务字面 LIKE 前缀及稳定的 canonical/id 排序，不做左模糊扫描。
CREATE INDEX idx_biz_tag_active_prefix ON biz_tag(tenant_key, canonical, id) WHERE deleted_token = 0;
COMMENT ON TABLE biz_tag IS '公共标签库；可信单部署命名空间，归档留历史，删后可建同名新标签';

CREATE TABLE biz_tag_object (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key  VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    object_type VARCHAR(32) NOT NULL CHECK (object_type IN (
        'STYLE','MATERIAL','SUPPLIER','TECH_PACK','ORDER','MRP_CALC','PURCHASE_ORDER',
        'INV_DOC','INV_COUNT','PRODUCTION_ORDER','PRODUCTION_RECEIPT','FG_DOC')),
    object_id   BIGINT NOT NULL CHECK (object_id > 0),
    company_id  BIGINT REFERENCES bas_company(id),
    version     INT NOT NULL DEFAULT 0 CHECK (version >= 0),
    create_by   BIGINT,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_biz_tag_object_tenant_id UNIQUE (tenant_key, id),
    CONSTRAINT uk_biz_tag_object_owner UNIQUE (tenant_key, object_type, object_id),
    CONSTRAINT ck_biz_tag_object_namespace CHECK (tenant_key ~ '^[A-Za-z0-9_-]{1,64}$')
);
-- 父删除/主体同步不传客户端 namespace；此索引有实际 object_type/object_id 前缀。
CREATE INDEX idx_biz_tag_object_owner ON biz_tag_object(object_type, object_id, id);
CREATE INDEX idx_biz_tag_object_company ON biz_tag_object(company_id);
COMMENT ON TABLE biz_tag_object IS '标签关联版本；固定 CASE 和真实父行锁保护多态目标完整性';
COMMENT ON COLUMN biz_tag_object.object_id IS '12 类真实父头的多态 ID，由 scm_tag_lock_owner 和父删除/主体同步触发器保护';

CREATE TABLE biz_tag_link (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key    VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    object_ref_id BIGINT NOT NULL,
    tag_id        BIGINT NOT NULL,
    seq           SMALLINT NOT NULL CHECK (seq BETWEEN 1 AND 30),
    CONSTRAINT fk_biz_tag_link_object FOREIGN KEY (tenant_key, object_ref_id)
        REFERENCES biz_tag_object(tenant_key, id),
    CONSTRAINT fk_biz_tag_link_tag FOREIGN KEY (tenant_key, tag_id)
        REFERENCES biz_tag(tenant_key, id),
    CONSTRAINT uk_biz_tag_link_tag UNIQUE (tenant_key, object_ref_id, tag_id),
    -- 只延迟顺序唯一性，允许一次 UPDATE 重排并保留原 link ID；事务结束仍最多 30 条。
    CONSTRAINT uk_biz_tag_link_seq UNIQUE (tenant_key, object_ref_id, seq) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_biz_tag_link_namespace CHECK (tenant_key ~ '^[A-Za-z0-9_-]{1,64}$')
);
CREATE INDEX idx_biz_tag_link_tag ON biz_tag_link(tenant_key, tag_id, object_ref_id);
COMMENT ON TABLE biz_tag_link IS '对象标签有序关联；复合部署外键，归档标签仅保留原关联，不可新挂';

-- 固定表白名单，不接受任意表名。FOR SHARE 同时阻挡 DELETE 和非键 company_id UPDATE。
-- 服务先锁 owner 再 registry；直接逆序 SQL 若已锁子行，用 NOWAIT 回滚，避免反向等父形成死锁。
CREATE FUNCTION scm_tag_lock_owner(kind TEXT, owner_id BIGINT) RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE actual_company BIGINT;
BEGIN
    CASE kind
        WHEN 'STYLE' THEN SELECT NULL::bigint INTO actual_company FROM sty_style WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'MATERIAL' THEN SELECT NULL::bigint INTO actual_company FROM bas_material WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'SUPPLIER' THEN SELECT NULL::bigint INTO actual_company FROM sup_supplier WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'TECH_PACK' THEN SELECT NULL::bigint INTO actual_company FROM tp_tech_pack WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'ORDER' THEN SELECT company_id INTO actual_company FROM ord_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'MRP_CALC' THEN SELECT company_id INTO actual_company FROM mrp_calc WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PURCHASE_ORDER' THEN SELECT company_id INTO actual_company FROM pur_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'INV_DOC' THEN SELECT company_id INTO actual_company FROM inv_doc WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'INV_COUNT' THEN SELECT company_id INTO actual_company FROM inv_count WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PRODUCTION_ORDER' THEN SELECT company_id INTO actual_company FROM prd_order WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'PRODUCTION_RECEIPT' THEN SELECT company_id INTO actual_company FROM prd_receipt WHERE id=owner_id FOR SHARE NOWAIT;
        WHEN 'FG_DOC' THEN SELECT company_id INTO actual_company FROM fg_doc WHERE id=owner_id FOR SHARE NOWAIT;
        ELSE RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='不支持的标签对象类型';
    END CASE;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签对象不存在'; END IF;
    RETURN actual_company;
END $$;

CREATE FUNCTION scm_tag_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签库只允许归档，历史不可删除';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.version<>0 OR NEW.deleted_token<>0 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='新标签必须为未归档初始版本';
        END IF;
    ELSE
        IF NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key OR OLD.deleted_token<>0
           OR NEW.create_by IS DISTINCT FROM OLD.create_by OR NEW.create_time<>OLD.create_time THEN
            RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签身份与归档历史不可改写';
        END IF;
        IF OLD.version=2147483647 OR NEW.version<>OLD.version+1 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='标签变更必须递增版本';
        END IF;
        IF NEW.deleted_token<>0 AND (NEW.name<>OLD.name OR NEW.canonical<>OLD.canonical) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='归档不能同时改名';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tag_guard BEFORE INSERT OR UPDATE OR DELETE ON biz_tag
    FOR EACH ROW EXECUTE FUNCTION scm_tag_guard();
CREATE TRIGGER tag_no_truncate BEFORE TRUNCATE ON biz_tag
    FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_guard();

CREATE FUNCTION scm_tag_object_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_company BIGINT;
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签关联不能通过 TRUNCATE 绕过归属与审计';
    END IF;
    IF TG_OP='DELETE' THEN
        PERFORM scm_tag_lock_owner(OLD.object_type,OLD.object_id);
        -- 先 link 后 object，link 的字段审计始终能找到真实父头。
        DELETE FROM biz_tag_link WHERE tenant_key=OLD.tenant_key AND object_ref_id=OLD.id;
        RETURN OLD;
    END IF;
    IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key
       OR NEW.object_type<>OLD.object_type OR NEW.object_id<>OLD.object_id
       OR NEW.create_by IS DISTINCT FROM OLD.create_by OR NEW.create_time<>OLD.create_time) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签对象身份不可改写';
    END IF;
    actual_company := scm_tag_lock_owner(NEW.object_type,NEW.object_id);
    IF NEW.company_id IS DISTINCT FROM actual_company THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='标签对象主体与真实父头不一致';
    END IF;
    IF TG_OP='INSERT' AND NEW.version<>0 THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='新标签对象必须为初始版本';
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.company_id IS DISTINCT FROM OLD.company_id AND NEW.version<>OLD.version THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='主体同步不能修改标签内容版本';
        END IF;
        IF NEW.version<>OLD.version AND (OLD.version=2147483647 OR NEW.version<>OLD.version+1) THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='标签关联版本必须逐次递增';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tag_object_guard BEFORE INSERT OR UPDATE OR DELETE ON biz_tag_object
    FOR EACH ROW EXECUTE FUNCTION scm_tag_object_guard();
CREATE TRIGGER tag_object_no_truncate BEFORE TRUNCATE ON biz_tag_object
    FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_object_guard();

CREATE FUNCTION scm_tag_link_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE ref biz_tag_object%ROWTYPE; binding biz_tag_link%ROWTYPE; archived BIGINT;
BEGIN
    IF TG_OP='TRUNCATE' THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签关联不能通过 TRUNCATE 绕过归属与审计';
    END IF;
    IF TG_OP='DELETE' THEN binding := OLD; ELSE binding := NEW; END IF;
    IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.tenant_key<>OLD.tenant_key
       OR NEW.object_ref_id<>OLD.object_ref_id OR NEW.tag_id<>OLD.tag_id) THEN
        RAISE EXCEPTION USING ERRCODE='55000', MESSAGE='标签关联身份不可换绑';
    END IF;
    -- 非本部署的值交给真实 namespace FK 拒绝；不能靠测试伪造第二合法 namespace。
    IF NOT EXISTS (SELECT 1 FROM sys_deployment_namespace WHERE tenant_id=binding.tenant_key) THEN
        RETURN binding;
    END IF;
    SELECT * INTO ref FROM biz_tag_object WHERE tenant_key=binding.tenant_key AND id=binding.object_ref_id;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签关联对象不存在'; END IF;
    PERFORM scm_tag_lock_owner(ref.object_type,ref.object_id);
    -- 直接 UPDATE/DELETE 已先锁 link；NOWAIT 阻止其逆向等待另一个 registry 持有者。
    SELECT * INTO ref FROM biz_tag_object WHERE tenant_key=binding.tenant_key AND id=binding.object_ref_id FOR UPDATE NOWAIT;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签关联对象不存在'; END IF;
    IF TG_OP<>'DELETE' THEN
        SELECT deleted_token INTO archived FROM biz_tag WHERE tenant_key=binding.tenant_key AND id=binding.tag_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签不存在'; END IF;
        IF TG_OP='INSERT' AND archived<>0 THEN
            RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='归档标签只能保留原有关联';
        END IF;
    END IF;
    RETURN binding;
END $$;
CREATE TRIGGER tag_link_guard BEFORE INSERT OR UPDATE OR DELETE ON biz_tag_link
    FOR EACH ROW EXECUTE FUNCTION scm_tag_link_guard();
CREATE TRIGGER tag_link_no_truncate BEFORE TRUNCATE ON biz_tag_link
    FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_link_guard();

CREATE FUNCTION scm_tag_parent_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='TRUNCATE' THEN
        IF EXISTS (SELECT 1 FROM biz_tag_object WHERE object_type=TG_ARGV[0]) THEN
            RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='有标签关联的对象不能通过 TRUNCATE 删除';
        END IF;
        RETURN NULL;
    END IF;
    IF TG_OP='UPDATE' THEN
        IF NEW.id<>OLD.id AND EXISTS (
            SELECT 1 FROM biz_tag_object WHERE object_type=TG_ARGV[0] AND object_id=OLD.id) THEN
            RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='有标签关联的对象不能改写 ID';
        END IF;
        RETURN NEW;
    END IF;
    -- BEFORE DELETE：父头仍存在且已被本事务锁住，registry 清理会先删除 link。
    DELETE FROM biz_tag_object WHERE object_type=TG_ARGV[0] AND object_id=OLD.id;
    RETURN OLD;
END $$;

CREATE FUNCTION scm_tag_parent_company() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.company_id IS DISTINCT FROM OLD.company_id THEN
        UPDATE biz_tag_object SET company_id=NEW.company_id,update_by=NEW.update_by,update_time=clock_timestamp()
        WHERE object_type=TG_ARGV[0] AND object_id=NEW.id;
    END IF;
    RETURN NEW;
END $$;

-- 写明全部真实父表，禁止运行时拼接客户端传来的表名。
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON sty_style FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('STYLE');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON sty_style FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('STYLE');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON bas_material FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('MATERIAL');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON bas_material FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('MATERIAL');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON sup_supplier FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('SUPPLIER');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON sup_supplier FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('SUPPLIER');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON tp_tech_pack FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('TECH_PACK');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON tp_tech_pack FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('TECH_PACK');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON ord_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('ORDER');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON ord_order FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('ORDER');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON ord_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('ORDER');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON mrp_calc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('MRP_CALC');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON mrp_calc FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('MRP_CALC');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON mrp_calc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('MRP_CALC');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON pur_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('PURCHASE_ORDER');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON pur_order FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('PURCHASE_ORDER');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON pur_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('PURCHASE_ORDER');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON inv_doc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('INV_DOC');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON inv_doc FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('INV_DOC');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON inv_doc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('INV_DOC');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON inv_count FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('INV_COUNT');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON inv_count FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('INV_COUNT');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON inv_count FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('INV_COUNT');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON prd_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('PRODUCTION_ORDER');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON prd_order FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('PRODUCTION_ORDER');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON prd_order FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('PRODUCTION_ORDER');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON prd_receipt FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('PRODUCTION_RECEIPT');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON prd_receipt FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('PRODUCTION_RECEIPT');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON prd_receipt FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('PRODUCTION_RECEIPT');
CREATE TRIGGER tag_parent_guard BEFORE DELETE OR UPDATE OF id ON fg_doc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_guard('FG_DOC');
CREATE TRIGGER tag_parent_truncate BEFORE TRUNCATE ON fg_doc FOR EACH STATEMENT EXECUTE FUNCTION scm_tag_parent_guard('FG_DOC');
CREATE TRIGGER tag_parent_company AFTER UPDATE OF company_id ON fg_doc FOR EACH ROW EXECUTE FUNCTION scm_tag_parent_company('FG_DOC');

-- 本增量独立白名单，不修改 V36、FG 审计函数或 system 的公开查询授权。
CREATE FUNCTION scm_tag_audit_project(table_kind TEXT, row_data JSONB) RETURNS JSONB LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE fields TEXT[]; field_name TEXT; result JSONB := '{}'::jsonb; value JSONB;
BEGIN
    fields := CASE table_kind
        WHEN 'biz_tag' THEN ARRAY['id','name','canonical','version','deleted_token','create_by','update_by']
        WHEN 'biz_tag_object' THEN ARRAY['id','object_type','object_id','company_id','version','create_by','update_by']
        WHEN 'biz_tag_link' THEN ARRAY['id','object_ref_id','tag_id','seq']
        ELSE ARRAY[]::text[] END;
    FOREACH field_name IN ARRAY fields LOOP
        IF row_data ? field_name THEN
            value := row_data->field_name;
            IF (field_name='id' OR field_name='deleted_token' OR field_name LIKE '%\_id' ESCAPE '\'
                OR field_name LIKE '%\_by' ESCAPE '\') AND value<>'null'::jsonb THEN
                value := to_jsonb(row_data->>field_name);
            END IF;
            result := result || jsonb_build_object(field_name,value);
        END IF;
    END LOOP;
    RETURN result;
END $$;

CREATE FUNCTION scm_capture_tag_diff() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE scm_tag_audit_project(TG_TABLE_NAME,to_jsonb(OLD)) END;
    after_row JSONB := CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE scm_tag_audit_project(TG_TABLE_NAME,to_jsonb(NEW)) END;
    actual JSONB := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    changes JSONB; namespace TEXT; owner_kind TEXT; owner_id BIGINT;
    root_name TEXT; domain TEXT; actor TEXT := NULLIF(current_setting('scm.audit_actor',true),'');
    actor_id BIGINT;
BEGIN
    changes := scm_audit_diff(before_row,after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;
    SELECT tenant_id INTO STRICT namespace FROM sys_deployment_namespace WHERE singleton;
    IF actual->>'tenant_key'<>namespace THEN
        RAISE EXCEPTION USING ERRCODE='23503', MESSAGE='标签命名空间与部署不一致';
    END IF;
    IF TG_TABLE_NAME='biz_tag' THEN
        root_name := 'biz_tag'; owner_id := (actual->>'id')::bigint;
    ELSE
        IF TG_TABLE_NAME='biz_tag_object' THEN
            owner_kind := actual->>'object_type'; owner_id := (actual->>'object_id')::bigint;
        ELSE
            SELECT object_type,object_id INTO STRICT owner_kind,owner_id FROM biz_tag_object
            WHERE tenant_key=namespace AND id=(actual->>'object_ref_id')::bigint;
        END IF;
        root_name := CASE owner_kind
            WHEN 'STYLE' THEN 'sty_style' WHEN 'MATERIAL' THEN 'bas_material' WHEN 'SUPPLIER' THEN 'sup_supplier'
            WHEN 'TECH_PACK' THEN 'tp_tech_pack' WHEN 'ORDER' THEN 'ord_order' WHEN 'MRP_CALC' THEN 'mrp_calc'
            WHEN 'PURCHASE_ORDER' THEN 'pur_order' WHEN 'INV_DOC' THEN 'inv_doc' WHEN 'INV_COUNT' THEN 'inv_count'
            WHEN 'PRODUCTION_ORDER' THEN 'prd_order' WHEN 'PRODUCTION_RECEIPT' THEN 'prd_receipt' WHEN 'FG_DOC' THEN 'fg_doc'
            ELSE NULL END;
        IF root_name IS NULL THEN RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='不支持的标签审计归属'; END IF;
    END IF;
    domain := CASE WHEN root_name IN ('ord_order','fg_doc') THEN 'ORDER'
                   WHEN root_name IN ('prd_order','prd_receipt') THEN 'PRODUCTION' ELSE 'COST' END;
    IF actor ~ '^[0-9]{1,19}$' THEN
        IF actor::numeric<=9223372036854775807 THEN actor_id := actor::bigint; END IF;
    END IF;
    INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,
        actor_user_id,client_ip,request_id,operation,transaction_id)
    VALUES(namespace,TG_TABLE_NAME,(actual->>'id')::bigint,root_name,owner_id,domain,TG_OP,changes,actor_id,
        NULLIF(left(current_setting('scm.audit_ip',true),64),''),
        NULLIF(left(current_setting('scm.audit_request',true),64),''),
        NULLIF(left(current_setting('scm.audit_operation',true),255),''),txid_current());
    RETURN NULL;
END $$;
CREATE TRIGGER tag_field_audit AFTER INSERT OR UPDATE OR DELETE ON biz_tag FOR EACH ROW EXECUTE FUNCTION scm_capture_tag_diff();
CREATE TRIGGER tag_object_field_audit AFTER INSERT OR UPDATE OR DELETE ON biz_tag_object FOR EACH ROW EXECUTE FUNCTION scm_capture_tag_diff();
CREATE TRIGGER tag_link_field_audit AFTER INSERT OR UPDATE OR DELETE ON biz_tag_link FOR EACH ROW EXECUTE FUNCTION scm_capture_tag_diff();

-- 可见的资料目录页；仅授予已存在的角色，绝不创建 prod_manager。
INSERT INTO sys_menu(parent_id,name,type,path,component,perms,icon,sort,visible,admin_only)
SELECT id,'公共标签库',2,'/tags','biz/tag/index','biz:tag:list','PriceTag',60,true,false
FROM sys_menu WHERE parent_id=0 AND path='/master' AND NOT deleted;
INSERT INTO sys_menu(parent_id,name,type,perms,sort,visible,admin_only)
SELECT id,'管理标签',3,'biz:tag:manage',1,true,false FROM sys_menu WHERE perms='biz:tag:list' AND NOT deleted;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.perms='biz:tag:list' AND NOT m.deleted
WHERE NOT r.deleted AND r.code IN ('dev','dev_lead','sales','merchandiser','buyer','warehouse','finance','manager','prod_manager')
ON CONFLICT DO NOTHING;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.perms='biz:tag:manage' AND NOT m.deleted
WHERE NOT r.deleted AND r.code='manager' ON CONFLICT DO NOTHING;
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT rm.role_id,m.parent_id FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
WHERE m.perms='biz:tag:list' AND m.parent_id<>0 AND NOT m.deleted ON CONFLICT DO NOTHING;
