-- Expand only: 新建小表、NOT VALID 检查约束；不改旧迁移，不批量回填历史账。
SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS biz_http_action (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    company_id BIGINT REFERENCES bas_company(id),
    operation VARCHAR(64) NOT NULL,
    request_key UUID NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PROCESSING','COMPLETED')),
    response_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_biz_http_action UNIQUE (tenant_code,user_id,operation,request_key),
    CONSTRAINT ck_biz_http_completion CHECK (
        (state='PROCESSING' AND response_json IS NULL AND completed_at IS NULL)
        OR (state='COMPLETED' AND response_json IS NOT NULL AND completed_at IS NOT NULL))
);
COMMENT ON TABLE biz_http_action IS 'HTTP建单动作幂等记录；认领、业务、完成同事务，禁止删除后复用历史动作键';
COMMENT ON COLUMN biz_http_action.tenant_code IS '服务端部署命名空间，不接受客户端指定租户';
COMMENT ON COLUMN biz_http_action.response_json IS '创建结果最小R响应；重放仍检查当前接口权限及对象可见性';
CREATE INDEX IF NOT EXISTS idx_biz_http_action_user ON biz_http_action(user_id);
CREATE INDEX IF NOT EXISTS idx_biz_http_action_company ON biz_http_action(company_id);

-- PostgreSQL 的 NUMERIC 支持 NaN，普通 >=0 检查挡不住 NaN；集中为现有精确数值列加有限数门禁。
-- NOT VALID 不扫描旧大表，但从迁移提交开始约束每一次新写入；历史检查另行 VALIDATE。
DO $guard$
DECLARE col record; constraint_name text;
BEGIN
    FOR col IN SELECT table_name,column_name FROM information_schema.columns
               WHERE table_schema=current_schema() AND data_type='numeric'
    LOOP
        constraint_name := 'ck_finite_' || col.column_name;
        IF NOT EXISTS (SELECT 1 FROM pg_constraint
                       WHERE conrelid=format('%I',col.table_name)::regclass AND conname=constraint_name) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IS NULL OR %I::text NOT IN (''NaN'',''Infinity'',''-Infinity'')) NOT VALID',
                           col.table_name,constraint_name,col.column_name,col.column_name);
        END IF;
    END LOOP;
END $guard$;

-- 库存方向由单据类型决定，不能用负的请求数量逆向入账；合法零盘点、零单价保留。
DO $bounds$
DECLARE item record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('inv_stock','ck_prod_stock_nonnegative','qty >= 0'),
        ('inv_stock_log','ck_prod_log_balance_nonnegative','balance_qty >= 0'),
        ('inv_doc_item','ck_prod_doc_item_bounds','qty > 0 AND (purchase_qty IS NULL OR purchase_qty > 0) AND (conversion_factor IS NULL OR conversion_factor > 0) AND (price IS NULL OR price >= 0) AND (amount IS NULL OR amount >= 0)'),
        ('inv_count_item','ck_prod_count_nonnegative','book_qty >= 0 AND (counted_qty IS NULL OR counted_qty >= 0)'),
        ('pur_order_item','ck_prod_purchase_bounds','conversion_factor > 0 AND (price IS NULL OR price >= 0) AND (amount IS NULL OR amount >= 0) AND received_qty >= returned_qty')
    ) AS checks(table_name,constraint_name,expression)
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid=format('%I',item.table_name)::regclass AND conname=item.constraint_name) THEN
            EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s) NOT VALID',item.table_name,item.constraint_name,item.expression);
        END IF;
    END LOOP;
END $bounds$;
