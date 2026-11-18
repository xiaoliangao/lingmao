SELECT set_config('lingmao.upload_lock_timeout', current_setting('lock_timeout'), false);
SELECT set_config('lingmao.upload_statement_timeout', current_setting('statement_timeout'), false);
SET lock_timeout = '5s';
SET statement_timeout = '5min';

CREATE TABLE IF NOT EXISTS sys_upload_file (
    file_url VARCHAR(255) PRIMARY KEY,
    tenant_key VARCHAR(64) NOT NULL,
    thumb_url VARCHAR(255) UNIQUE,
    owner_user_id BIGINT REFERENCES sys_user(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('WRITING','READY','DELETING','DELETED')),
    byte_size BIGINT NOT NULL DEFAULT 0 CHECK (byte_size BETWEEN 0 AND 104857600),
    sha256 CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    touched_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
COMMENT ON TABLE sys_upload_file IS '新上传文件生命周期与孤儿清理；历史未登记文件不自动删除';
CREATE INDEX IF NOT EXISTS idx_sys_upload_file_owner ON sys_upload_file(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_sys_upload_file_cleanup ON sys_upload_file(tenant_key,touched_at,file_url)
    WHERE status IN ('WRITING','READY','DELETING');

-- Attachment references are checked during bounded cleanup, without scanning their complete history.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_biz_attachment_file_url ON biz_attachment(file_url);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_biz_attachment_thumb_url ON biz_attachment(thumb_url);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sup_cert_file_url ON sup_supplier_cert(file_url);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_tp_process_image_file_url ON tp_process_image(file_url);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_tp_process_image_thumb_url ON tp_process_image(thumb_url);

-- An attachment reference and the cleaner take the same catalog row lock. Filesystem I/O is never in this transaction.
-- Old, uncatalogued files retain their existing lifecycle; only files created after this migration are managed here.
CREATE OR REPLACE FUNCTION scm_upload_reference_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE document jsonb; item record;
BEGIN
    document := CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
    FOR item IN SELECT file_url,status FROM sys_upload_file
        WHERE file_url IN (document->>'file_url', document->>'thumb_url')
           OR thumb_url IN (document->>'file_url', document->>'thumb_url')
        ORDER BY file_url FOR UPDATE
    LOOP
        IF TG_OP <> 'DELETE' AND item.status <> 'READY' THEN
            RAISE EXCEPTION '上传文件尚未完成或已清理，请重新上传' USING ERRCODE='P2019';
        END IF;
        UPDATE sys_upload_file SET touched_at=clock_timestamp() WHERE file_url=item.file_url AND status='READY';
    END LOOP;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE table_name text;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['biz_attachment','sup_supplier_cert','tp_process_image'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgrelid=table_name::regclass AND tgname='trg_upload_reference_guard') THEN
            EXECUTE format('CREATE TRIGGER trg_upload_reference_guard BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION scm_upload_reference_guard()', table_name);
        END IF;
    END LOOP;
    IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
        WHERE c.relname IN ('idx_biz_attachment_file_url','idx_biz_attachment_thumb_url','idx_sup_cert_file_url',
                             'idx_tp_process_image_file_url','idx_tp_process_image_thumb_url')
          AND (NOT i.indisvalid OR NOT i.indisready)) THEN
        RAISE EXCEPTION '上传引用索引无效；请 CONCURRENTLY 删除无效同名索引后重试迁移';
    END IF;
END $$;

SELECT set_config('lock_timeout', current_setting('lingmao.upload_lock_timeout'), false);
SELECT set_config('statement_timeout', current_setting('lingmao.upload_statement_timeout'), false);
