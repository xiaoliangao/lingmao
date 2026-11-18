-- Expand only: new audit relation, nullable metadata and short bounded trigger installation.
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '30s';

CREATE TABLE sys_data_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL,
    table_name VARCHAR(64) NOT NULL,
    row_id BIGINT NOT NULL,
    root_table VARCHAR(64) NOT NULL,
    root_id BIGINT NOT NULL,
    price_domain VARCHAR(16) NOT NULL CHECK(price_domain IN ('COST','ORDER','PRODUCTION')),
    action VARCHAR(8) NOT NULL CHECK(action IN ('INSERT','UPDATE','DELETE','REPLACE')),
    previous_row_id BIGINT,
    diff JSONB NOT NULL,
    actor_user_id BIGINT,
    client_ip VARCHAR(64),
    request_id VARCHAR(64),
    operation VARCHAR(255),
    transaction_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_data_log_row ON sys_data_log(tenant_key,table_name,row_id,id DESC);
CREATE INDEX idx_data_log_request ON sys_data_log(request_id,id DESC);
CREATE INDEX idx_data_log_root ON sys_data_log(tenant_key,root_table,root_id,id DESC);
CREATE INDEX idx_data_log_transaction ON sys_data_log(tenant_key,root_table,root_id,transaction_id,id DESC);
-- Replacement lookups execute once per saved child. A root/transaction index
-- alone repeatedly scans the growing DELETE/INSERT/REPLACE history of a large
-- BOM. Keep each proven old identity/parent in the searchable prefix instead.
-- TG_TABLE_NAME is a name-typed SPI parameter; put table_name after the identity
-- so its residual comparison cannot separate that prefix or the id ordering.
CREATE INDEX idx_data_log_delete_bom_parent ON sys_data_log(
    tenant_key,root_table,root_id,transaction_id,((diff->'bom_item_id'->>'old')),id DESC,table_name)
    WHERE action='DELETE';
CREATE INDEX idx_data_log_previous_identity ON sys_data_log(
    tenant_key,root_table,root_id,transaction_id,previous_row_id,table_name)
    WHERE previous_row_id IS NOT NULL;
CREATE INDEX idx_data_log_deleted_identity ON sys_data_log(
    tenant_key,root_table,root_id,transaction_id,row_id,table_name)
    WHERE action='DELETE';
CREATE INDEX idx_data_log_replaced_bom_parent ON sys_data_log(
    tenant_key,root_table,root_id,transaction_id,row_id,id DESC)
    WHERE action='REPLACE' AND table_name='tp_bom_item';
COMMENT ON TABLE sys_data_log IS 'Append-only field changes, committed or rolled back with the business mutation';
ALTER TABLE sys_oper_log ALTER COLUMN trace_id TYPE VARCHAR(64);
ALTER TABLE biz_doc_log ADD COLUMN request_id VARCHAR(64), ADD COLUMN client_ip VARCHAR(64);
INSERT INTO sys_menu(parent_id,name,type,perms,sort,admin_only)
SELECT id,'字段审计查询',3,'system:audit:query',99,false FROM sys_menu
WHERE perms='system:log:list' AND NOT deleted;

CREATE FUNCTION scm_reject_audit_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION USING ERRCODE='P2041', MESSAGE='Audit records are append-only';
END $$;
CREATE TRIGGER data_log_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON sys_data_log
    FOR EACH STATEMENT EXECUTE FUNCTION scm_reject_audit_mutation();
CREATE TRIGGER oper_log_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON sys_oper_log
    FOR EACH STATEMENT EXECUTE FUNCTION scm_reject_audit_mutation();
CREATE TRIGGER doc_log_append_only BEFORE UPDATE OR TRUNCATE ON biz_doc_log
    FOR EACH STATEMENT EXECUTE FUNCTION scm_reject_audit_mutation();
-- Old application versions delete draft history as part of deleting the draft. Retain those rows
-- without aborting the old business transaction during an Expand-Contract rolling upgrade.
CREATE FUNCTION scm_retain_doc_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$;
CREATE TRIGGER doc_log_retain_delete BEFORE DELETE ON biz_doc_log
    FOR EACH ROW EXECUTE FUNCTION scm_retain_doc_history();

CREATE FUNCTION scm_doc_log_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.request_id := NULLIF(current_setting('scm.audit_request',true),'');
    NEW.client_ip := NULLIF(current_setting('scm.audit_ip',true),'');
    RETURN NEW;
END $$;
CREATE TRIGGER doc_log_context BEFORE INSERT ON biz_doc_log FOR EACH ROW EXECUTE FUNCTION scm_doc_log_context();

CREATE FUNCTION scm_audit_diff(before_row JSONB, after_row JSONB) RETURNS JSONB LANGUAGE SQL IMMUTABLE AS $$
    SELECT jsonb_object_agg(k,jsonb_build_object('old',before_row->k,'new',after_row->k))
      FROM (SELECT jsonb_object_keys(before_row) AS k UNION SELECT jsonb_object_keys(after_row) AS k) fields
      WHERE before_row->k IS DISTINCT FROM after_row->k
$$;

CREATE FUNCTION scm_capture_field_diff() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    before_row JSONB := CASE WHEN TG_OP='INSERT' THEN '{}'::jsonb ELSE to_jsonb(OLD) END;
    after_row JSONB := CASE WHEN TG_OP='DELETE' THEN '{}'::jsonb ELSE to_jsonb(NEW) END;
    changes JSONB;
    actor TEXT := NULLIF(current_setting('scm.audit_actor',true),'');
    namespace TEXT;
    actual_row JSONB := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    root_table_name TEXT := TG_TABLE_NAME;
    root_row_id BIGINT := (actual_row->>'id')::bigint;
    domain TEXT;
    hints JSONB := COALESCE(NULLIF(current_setting('scm.audit_replacements',true),'')::jsonb,'{}'::jsonb);
    previous_id BIGINT;
    previous_parent BIGINT;
    hint_id TEXT;
    replaced_before JSONB;
BEGIN
    -- Passwords, session secrets and unbounded free-form import payloads never enter the audit store.
    before_row := before_row - ARRAY['password','token','secret','app_secret','legacy_attrs','content','create_time','update_time','create_by','update_by'];
    after_row := after_row - ARRAY['password','token','secret','app_secret','legacy_attrs','content','create_time','update_time','create_by','update_by'];
    changes := scm_audit_diff(before_row,after_row);
    IF changes IS NULL THEN RETURN NULL; END IF;
    SELECT tenant_id INTO namespace FROM sys_deployment_namespace WHERE singleton;
    namespace := COALESCE(namespace,'uninitialized');
    CASE TG_TABLE_NAME
        WHEN 'bas_material_sku' THEN root_table_name := 'bas_material'; root_row_id := (actual_row->>'material_id')::bigint;
        WHEN 'sup_supplier_change' THEN root_table_name := 'sup_supplier'; root_row_id := (actual_row->>'supplier_id')::bigint;
        WHEN 'tp_bom_item', 'tp_process', 'tp_cost_item' THEN
            root_table_name := 'tp_tech_pack'; root_row_id := (actual_row->>'tech_pack_id')::bigint;
        WHEN 'tp_bom_item_sku', 'tp_bom_item_size' THEN
            root_table_name := 'tp_tech_pack';
            SELECT tech_pack_id INTO STRICT root_row_id FROM tp_bom_item WHERE id=(actual_row->>'bom_item_id')::bigint;
        WHEN 'ord_order_item', 'ord_change' THEN root_table_name := 'ord_order'; root_row_id := (actual_row->>'order_id')::bigint;
        WHEN 'ord_change_item' THEN
            root_table_name := 'ord_order';
            SELECT order_id INTO STRICT root_row_id FROM ord_change WHERE id=(actual_row->>'change_id')::bigint;
        WHEN 'pur_order_item' THEN root_table_name := 'pur_order'; root_row_id := (actual_row->>'purchase_order_id')::bigint;
        WHEN 'prd_order_item' THEN root_table_name := 'prd_order'; root_row_id := (actual_row->>'prd_order_id')::bigint;
        WHEN 'inv_doc_item' THEN root_table_name := 'inv_doc'; root_row_id := (actual_row->>'doc_id')::bigint;
        WHEN 'inv_count_item' THEN root_table_name := 'inv_count'; root_row_id := (actual_row->>'count_id')::bigint;
        ELSE NULL;
    END CASE;
    domain := CASE WHEN TG_TABLE_NAME LIKE 'ord\_%' THEN 'ORDER'
        WHEN TG_TABLE_NAME LIKE 'prd\_%' OR TG_TABLE_NAME='prc_price' AND actual_row->>'type'='PROCESS' THEN 'PRODUCTION'
        ELSE 'COST' END;
    INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,diff,actor_user_id,client_ip,request_id,operation,transaction_id)
    VALUES(COALESCE(namespace,'uninitialized'),TG_TABLE_NAME,
        (actual_row->>'id')::bigint,root_table_name,root_row_id,domain,TG_OP,changes,
        CASE WHEN actor ~ '^[0-9]{1,19}$' THEN actor::bigint ELSE NULL END,
        NULLIF(current_setting('scm.audit_ip',true),''),NULLIF(current_setting('scm.audit_request',true),''),
        NULLIF(current_setting('scm.audit_operation',true),''),txid_current());

    -- Preserve physical events above. Add a semantic replacement only with a verified old identity,
    -- never by assuming that a material/part/sequence is a unique logical business key.
    IF TG_OP='INSERT' AND hints->>'rootTable'=root_table_name AND hints->>'rootId'=root_row_id::text THEN
        IF TG_TABLE_NAME IN ('tp_bom_item','tp_process','pur_order_item') THEN
            hint_id := hints->'tables'->TG_TABLE_NAME->>(actual_row->>'seq');
            IF hint_id ~ '^[0-9]{1,19}$' THEN previous_id := hint_id::bigint; END IF;
        ELSIF TG_TABLE_NAME IN ('tp_bom_item_sku','tp_bom_item_size') THEN
            SELECT previous_row_id INTO previous_parent FROM sys_data_log
              WHERE tenant_key=namespace AND root_table=root_table_name AND root_id=root_row_id
                AND transaction_id=txid_current() AND table_name='tp_bom_item' AND action='REPLACE'
                AND row_id=(actual_row->>'bom_item_id')::bigint ORDER BY id DESC LIMIT 1;
            -- These child dimensions have actual database UNIQUE constraints within one BOM row.
            SELECT row_id INTO previous_id FROM sys_data_log
              WHERE tenant_key=namespace AND root_table=root_table_name AND root_id=root_row_id
                AND transaction_id=txid_current() AND table_name=TG_TABLE_NAME AND action='DELETE'
                AND diff->'bom_item_id'->>'old'=previous_parent::text
                AND (diff->'size_code'->'old') IS NOT DISTINCT FROM actual_row->'size_code'
                AND (TG_TABLE_NAME='tp_bom_item_size' OR (diff->'color_id'->'old') IS NOT DISTINCT FROM actual_row->'color_id')
              ORDER BY id DESC LIMIT 1;
        END IF;
        IF previous_id IS NOT NULL AND NOT EXISTS(
            SELECT 1 FROM sys_data_log WHERE tenant_key=namespace AND root_table=root_table_name AND root_id=root_row_id
                AND transaction_id=txid_current() AND table_name=TG_TABLE_NAME AND previous_row_id=previous_id) THEN
            SELECT jsonb_object_agg(e.key,e.value->'old') INTO replaced_before
              FROM sys_data_log d CROSS JOIN LATERAL jsonb_each(d.diff) e
              WHERE d.tenant_key=namespace AND d.root_table=root_table_name AND d.root_id=root_row_id
                AND d.transaction_id=txid_current() AND d.table_name=TG_TABLE_NAME AND d.row_id=previous_id AND d.action='DELETE';
            IF replaced_before IS NOT NULL THEN
                INSERT INTO sys_data_log(tenant_key,table_name,row_id,root_table,root_id,price_domain,action,previous_row_id,diff,
                    actor_user_id,client_ip,request_id,operation,transaction_id)
                VALUES(namespace,TG_TABLE_NAME,(actual_row->>'id')::bigint,root_table_name,root_row_id,domain,'REPLACE',previous_id,
                    scm_audit_diff(replaced_before,after_row),CASE WHEN actor ~ '^[0-9]{1,19}$' THEN actor::bigint ELSE NULL END,
                    NULLIF(current_setting('scm.audit_ip',true),''),NULLIF(current_setting('scm.audit_request',true),''),
                    NULLIF(current_setting('scm.audit_operation',true),''),txid_current());
            END IF;
        END IF;
    END IF;
    RETURN NULL;
END $$;

DO $$ DECLARE target TEXT; BEGIN
    FOREACH target IN ARRAY ARRAY['bas_company','bas_customer','bas_material','bas_material_sku','bas_warehouse',
        'sty_style','sup_supplier','sup_supplier_change','prc_price','tp_tech_pack','tp_bom_item','tp_bom_item_sku',
        'tp_bom_item_size','tp_process','tp_cost_item','ord_order','ord_order_item','ord_change','ord_change_item',
        'pur_order','pur_order_item','prd_order','prd_order_item','inv_doc','inv_doc_item','inv_stock','inv_count','inv_count_item'] LOOP
        EXECUTE format('CREATE TRIGGER field_audit AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION scm_capture_field_diff()',target);
    END LOOP;
END $$;
