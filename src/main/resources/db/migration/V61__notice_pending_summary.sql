-- P33 同事务提醒摘要。这里只追加结构，不在 Flyway/启动事务里回填整库。
-- namespace 由 DeploymentNamespaceGuard 在迁移后初始化；维护入口随后创建状态行。
SET LOCAL lock_timeout = '5s';

CREATE TABLE biz_doc_notice_pending (
    tenant_key       VARCHAR(64) NOT NULL REFERENCES sys_deployment_namespace(tenant_id),
    doc_type         VARCHAR(32) NOT NULL,
    doc_id           BIGINT NOT NULL,
    pending_count    BIGINT NOT NULL DEFAULT 0 CHECK (pending_count >= 0),
    latest_time      TIMESTAMPTZ,
    latest_notice_id BIGINT,
    revision         BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    initialized      BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (tenant_key, doc_type, doc_id),
    CHECK ((pending_count = 0 AND latest_time IS NULL AND latest_notice_id IS NULL)
        OR (pending_count > 0 AND latest_time IS NOT NULL AND latest_notice_id IS NOT NULL))
);
CREATE INDEX idx_notice_pending_summary_seek
    ON biz_doc_notice_pending (tenant_key, latest_time DESC, doc_type DESC, doc_id DESC)
    WHERE pending_count > 0;
COMMENT ON TABLE biz_doc_notice_pending IS '未处理提醒同步摘要（零计数记录保留作单据键锁）';
COMMENT ON COLUMN biz_doc_notice_pending.tenant_key IS '来自数据库单例的可信部署 namespace，不接受请求指定';
COMMENT ON COLUMN biz_doc_notice_pending.doc_type IS '真实目标单据类型；与 doc_id 组成多态键';
COMMENT ON COLUMN biz_doc_notice_pending.doc_id IS '真实目标单据 ID；沿用来源表的多态关系';
COMMENT ON COLUMN biz_doc_notice_pending.pending_count IS 'handled_time 为空的完整通知条数';
COMMENT ON COLUMN biz_doc_notice_pending.latest_time IS '未处理通知最大 create_time';
COMMENT ON COLUMN biz_doc_notice_pending.latest_notice_id IS '同时间取最大 ID 的派生指针；FK 的提交检查会与删除/摘要锁成环，依靠同事务维护及对账';
COMMENT ON COLUMN biz_doc_notice_pending.revision IS '每条来源语句每个真实变化键推进一次；锁内回填也推进一次';
COMMENT ON COLUMN biz_doc_notice_pending.initialized IS '已在持有本键锁时从来源初始化；未初始化记录不能作为有效摘要';

CREATE TABLE biz_doc_notice_pending_state (
    tenant_key             VARCHAR(64) PRIMARY KEY REFERENCES sys_deployment_namespace(tenant_id),
    backfill_after_type    VARCHAR(32),
    backfill_after_id      BIGINT,
    backfill_complete      BOOLEAN NOT NULL DEFAULT false,
    reconcile_after_type   VARCHAR(32),
    reconcile_after_id     BIGINT,
    reconcile_complete     BOOLEAN NOT NULL DEFAULT false,
    reconcile_mismatches   BIGINT NOT NULL DEFAULT 0 CHECK (reconcile_mismatches >= 0),
    ready                  BOOLEAN NOT NULL DEFAULT false,
    use_summary            BOOLEAN NOT NULL DEFAULT false,
    update_time            TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((backfill_after_type IS NULL) = (backfill_after_id IS NULL)),
    CHECK ((reconcile_after_type IS NULL) = (reconcile_after_id IS NULL)),
    CHECK (NOT ready OR (backfill_complete AND reconcile_complete AND reconcile_mismatches = 0)),
    CHECK (NOT use_summary OR ready)
);
COMMENT ON TABLE biz_doc_notice_pending_state IS '提醒摘要回填、逐键对账进度与显式读路径开关';
COMMENT ON COLUMN biz_doc_notice_pending_state.backfill_after_id IS '回填的复合游标位置，不是业务行引用；目标删除后仍须保留进度';
COMMENT ON COLUMN biz_doc_notice_pending_state.reconcile_after_id IS '对账的复合游标位置，不是业务行引用；目标删除后仍须保留进度';
COMMENT ON COLUMN biz_doc_notice_pending_state.ready IS '本轮回填及逐键对账完成且零差异；不表示跨批全库单一快照';
COMMENT ON COLUMN biz_doc_notice_pending_state.use_summary IS '仅控制读取；关闭时所有来源触发器继续同步维护';

CREATE FUNCTION scm_notice_pending_namespace() RETURNS VARCHAR
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public AS $$
DECLARE result VARCHAR(64);
BEGIN
    SELECT tenant_id INTO STRICT result FROM sys_deployment_namespace WHERE singleton;
    RETURN result;
END;
$$;

CREATE FUNCTION scm_notice_pending_initialize() RETURNS void
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO biz_doc_notice_pending_state(tenant_key)
    VALUES (scm_notice_pending_namespace()) ON CONFLICT (tenant_key) DO NOTHING;
END;
$$;

-- 返回已锁定的最新版本。INSERT 和 SELECT 必须是两条 SQL：RC 等待之后重新取快照。
-- 零记录不删除，不依赖在缺失键上的 FOR UPDATE 来阻止另一事务创建同键。
CREATE FUNCTION scm_notice_pending_lock(p_tenant VARCHAR, p_type VARCHAR, p_doc BIGINT)
RETURNS biz_doc_notice_pending
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE locked biz_doc_notice_pending;
BEGIN
    INSERT INTO biz_doc_notice_pending(tenant_key, doc_type, doc_id)
    VALUES (p_tenant, p_type, p_doc) ON CONFLICT (tenant_key, doc_type, doc_id) DO NOTHING;
    SELECT * INTO locked FROM biz_doc_notice_pending
    WHERE tenant_key = p_tenant AND doc_type = p_type AND doc_id = p_doc FOR UPDATE;
    IF NOT FOUND THEN
        -- RR 可能看不到刚产生冲突的锚点，必须重启整个事务，不能伪造空摘要。
        RAISE EXCEPTION 'Notice summary lock requires a fresh transaction' USING ERRCODE = '40001';
    END IF;
    RETURN locked;
END;
$$;

-- 只用于初始回填/对账；日常增量不重复 COUNT 整组。调用方已取得摘要锁，
-- 这次独立 SELECT 的同一快照同时给出 count 与 (create_time,id) 的最大值。
CREATE FUNCTION scm_notice_pending_actual(p_type VARCHAR, p_doc BIGINT)
RETURNS TABLE(pending_count BIGINT, latest_time TIMESTAMPTZ, latest_notice_id BIGINT)
LANGUAGE sql STABLE SET search_path = pg_catalog, public AS $$
    SELECT totals.pending_count, latest.create_time, latest.id
    FROM (SELECT COUNT(*) AS pending_count FROM biz_doc_notice
          WHERE doc_type = p_type AND doc_id = p_doc AND handled_time IS NULL) totals
    LEFT JOIN LATERAL (
        SELECT create_time, id FROM biz_doc_notice
        WHERE doc_type = p_type AND doc_id = p_doc AND handled_time IS NULL
        ORDER BY create_time DESC, id DESC LIMIT 1
    ) latest ON true
$$;

CREATE FUNCTION scm_notice_pending_refresh(p_tenant VARCHAR, p_type VARCHAR, p_doc BIGINT)
RETURNS void LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE actual RECORD;
BEGIN
    PERFORM scm_notice_pending_lock(p_tenant, p_type, p_doc);
    SELECT * INTO STRICT actual FROM scm_notice_pending_actual(p_type, p_doc);
    UPDATE biz_doc_notice_pending
    SET pending_count = actual.pending_count, latest_time = actual.latest_time,
        latest_notice_id = actual.latest_notice_id, initialized = true, revision = revision + 1
    WHERE tenant_key = p_tenant AND doc_type = p_type AND doc_id = p_doc;
END;
$$;

CREATE FUNCTION scm_notice_pending_apply_change(p_tenant VARCHAR, p_type VARCHAR, p_doc BIGINT,
        p_delta BIGINT, p_added_time TIMESTAMPTZ, p_added_id BIGINT,
        p_removed_time TIMESTAMPTZ, p_removed_id BIGINT)
RETURNS void LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE locked biz_doc_notice_pending; next_count BIGINT; next_time TIMESTAMPTZ; next_id BIGINT;
BEGIN
    locked := scm_notice_pending_lock(p_tenant, p_type, p_doc);
    IF NOT locked.initialized THEN
        -- AFTER STATEMENT 的来源已包含本语句变化；初始化之后不能再加一次 delta。
        PERFORM scm_notice_pending_refresh(p_tenant, p_type, p_doc);
        RETURN;
    END IF;
    next_count := locked.pending_count + p_delta;
    IF next_count < 0 THEN
        RAISE EXCEPTION 'Notice summary count mismatch; reconcile before retrying' USING ERRCODE = '23514';
    END IF;
    next_time := locked.latest_time; next_id := locked.latest_notice_id;
    IF next_count = 0 THEN
        next_time := NULL; next_id := NULL;
    ELSIF (locked.latest_time, locked.latest_notice_id) = (p_removed_time, p_removed_id) THEN
        -- 与取锁分开；RC 必须在等锁结束后重取快照。这里不能再 FOR UPDATE 锁通知行。
        SELECT create_time, id INTO next_time, next_id FROM biz_doc_notice
        WHERE doc_type = p_type AND doc_id = p_doc AND handled_time IS NULL
        ORDER BY create_time DESC, id DESC LIMIT 1;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Notice summary latest mismatch; reconcile before retrying' USING ERRCODE = '23514';
        END IF;
    ELSIF p_added_id IS NOT NULL AND (next_id IS NULL OR (p_added_time, p_added_id) > (next_time, next_id)) THEN
        next_time := p_added_time; next_id := p_added_id;
    END IF;
    -- 净 0 的真实成员/排序变化也必须写版本；否则 RR 的旧快照可能漏检冲突。
    UPDATE biz_doc_notice_pending
    SET pending_count = next_count, latest_time = next_time, latest_notice_id = next_id,
        revision = revision + 1
    WHERE tenant_key = p_tenant AND doc_type = p_type AND doc_id = p_doc;
END;
$$;

CREATE FUNCTION scm_notice_pending_changed() RETURNS trigger
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE tenant VARCHAR(64); changed RECORD;
BEGIN
    tenant := scm_notice_pending_namespace();
    IF TG_OP = 'INSERT' THEN
        FOR changed IN
            SELECT doc_type, doc_id, COUNT(*) AS delta, MAX(create_time) AS latest_time,
                   MAX(id) FILTER (WHERE position = 1) AS latest_id
            FROM (SELECT id, doc_type, doc_id, create_time,
                         ROW_NUMBER() OVER (PARTITION BY doc_type, doc_id ORDER BY create_time DESC, id DESC) AS position
                  FROM notice_new WHERE handled_time IS NULL) ranked
            GROUP BY doc_type, doc_id ORDER BY doc_type, doc_id
        LOOP
            PERFORM scm_notice_pending_apply_change(tenant, changed.doc_type, changed.doc_id,
                changed.delta, changed.latest_time, changed.latest_id, NULL, NULL);
        END LOOP;
    ELSIF TG_OP = 'DELETE' THEN
        FOR changed IN
            SELECT doc_type, doc_id, -COUNT(*) AS delta, MAX(create_time) AS latest_time,
                   MAX(id) FILTER (WHERE position = 1) AS latest_id
            FROM (SELECT id, doc_type, doc_id, create_time,
                         ROW_NUMBER() OVER (PARTITION BY doc_type, doc_id ORDER BY create_time DESC, id DESC) AS position
                  FROM notice_old WHERE handled_time IS NULL) ranked
            GROUP BY doc_type, doc_id ORDER BY doc_type, doc_id
        LOOP
            PERFORM scm_notice_pending_apply_change(tenant, changed.doc_type, changed.doc_id,
                changed.delta, NULL, NULL, changed.latest_time, changed.latest_id);
        END LOOP;
    ELSE
        FOR changed IN
            WITH old_pending AS (
                SELECT id, doc_type, doc_id, create_time FROM notice_old WHERE handled_time IS NULL
            ), new_pending AS (
                SELECT id, doc_type, doc_id, create_time FROM notice_new WHERE handled_time IS NULL
            ), changes AS (
                SELECT removed.*, -1 AS delta FROM (TABLE old_pending EXCEPT TABLE new_pending) removed
                UNION ALL
                SELECT added.*, 1 AS delta FROM (TABLE new_pending EXCEPT TABLE old_pending) added
            ), ranked AS (
                SELECT *, ROW_NUMBER() OVER (PARTITION BY doc_type, doc_id, delta ORDER BY create_time DESC, id DESC) AS position
                FROM changes
            )
            SELECT doc_type, doc_id, SUM(delta) AS delta,
                   MAX(create_time) FILTER (WHERE delta = 1 AND position = 1) AS added_time,
                   MAX(id) FILTER (WHERE delta = 1 AND position = 1) AS added_id,
                   MAX(create_time) FILTER (WHERE delta = -1 AND position = 1) AS removed_time,
                   MAX(id) FILTER (WHERE delta = -1 AND position = 1) AS removed_id
            FROM ranked GROUP BY doc_type, doc_id ORDER BY doc_type, doc_id
        LOOP
            PERFORM scm_notice_pending_apply_change(tenant, changed.doc_type, changed.doc_id,
                changed.delta, changed.added_time, changed.added_id, changed.removed_time, changed.removed_id);
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER notice_pending_insert AFTER INSERT ON biz_doc_notice
    REFERENCING NEW TABLE AS notice_new FOR EACH STATEMENT EXECUTE FUNCTION scm_notice_pending_changed();
CREATE TRIGGER notice_pending_update AFTER UPDATE ON biz_doc_notice
    REFERENCING OLD TABLE AS notice_old NEW TABLE AS notice_new
    FOR EACH STATEMENT EXECUTE FUNCTION scm_notice_pending_changed();
CREATE TRIGGER notice_pending_delete AFTER DELETE ON biz_doc_notice
    REFERENCING OLD TABLE AS notice_old FOR EACH STATEMENT EXECUTE FUNCTION scm_notice_pending_changed();

-- 来源与全部摘要（包括零记录）的键并集。两边先各取有界键页，再合并，
-- 不将全库键装入数组。首批与后续批显式分支，避免 nullable OR 阻碍范围条件。
CREATE FUNCTION scm_notice_pending_keys(p_tenant VARCHAR, p_after_type VARCHAR, p_after_id BIGINT, p_limit INTEGER)
RETURNS TABLE(doc_type VARCHAR, doc_id BIGINT)
LANGUAGE plpgsql STABLE SET search_path = pg_catalog, public AS $$
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
        RAISE EXCEPTION 'Notice maintenance batch must be within 1..500' USING ERRCODE = '22023';
    END IF;
    IF p_after_type IS NULL THEN
        RETURN QUERY
            SELECT keys.doc_type, keys.doc_id FROM (
                (SELECT DISTINCT n.doc_type, n.doc_id FROM biz_doc_notice n WHERE n.handled_time IS NULL
                 ORDER BY n.doc_type, n.doc_id LIMIT p_limit)
                UNION
                (SELECT s.doc_type, s.doc_id FROM biz_doc_notice_pending s WHERE s.tenant_key = p_tenant
                 ORDER BY s.doc_type, s.doc_id LIMIT p_limit)
            ) keys ORDER BY keys.doc_type, keys.doc_id LIMIT p_limit;
    ELSE
        RETURN QUERY
            SELECT keys.doc_type, keys.doc_id FROM (
                (SELECT DISTINCT n.doc_type, n.doc_id FROM biz_doc_notice n
                 WHERE n.handled_time IS NULL AND (n.doc_type, n.doc_id) > (p_after_type, p_after_id)
                 ORDER BY n.doc_type, n.doc_id LIMIT p_limit)
                UNION
                (SELECT s.doc_type, s.doc_id FROM biz_doc_notice_pending s
                 WHERE s.tenant_key = p_tenant AND (s.doc_type, s.doc_id) > (p_after_type, p_after_id)
                 ORDER BY s.doc_type, s.doc_id LIMIT p_limit)
            ) keys ORDER BY keys.doc_type, keys.doc_id LIMIT p_limit;
    END IF;
END;
$$;

CREATE FUNCTION scm_notice_pending_restart() RETURNS void
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
BEGIN
    PERFORM scm_notice_pending_initialize();
    UPDATE biz_doc_notice_pending_state
    SET backfill_after_type = NULL, backfill_after_id = NULL, backfill_complete = false,
        reconcile_after_type = NULL, reconcile_after_id = NULL, reconcile_complete = false,
        reconcile_mismatches = 0, ready = false, use_summary = false, update_time = clock_timestamp()
    WHERE tenant_key = scm_notice_pending_namespace();
END;
$$;

CREATE FUNCTION scm_notice_pending_backfill(p_limit INTEGER)
RETURNS TABLE(processed INTEGER, complete BOOLEAN, mismatches BIGINT)
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE state biz_doc_notice_pending_state; key RECORD; last_type VARCHAR(32); last_id BIGINT; used INTEGER := 0;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
        RAISE EXCEPTION 'Notice maintenance batch must be within 1..500' USING ERRCODE = '22023';
    END IF;
    PERFORM scm_notice_pending_initialize();
    SELECT * INTO STRICT state FROM biz_doc_notice_pending_state
    WHERE tenant_key = scm_notice_pending_namespace() FOR UPDATE;
    IF state.backfill_complete THEN RETURN QUERY SELECT 0, true, 0::BIGINT; RETURN; END IF;
    last_type := state.backfill_after_type; last_id := state.backfill_after_id;
    FOR key IN SELECT * FROM scm_notice_pending_keys(state.tenant_key, last_type, last_id, p_limit)
    LOOP
        PERFORM scm_notice_pending_refresh(state.tenant_key, key.doc_type, key.doc_id);
        last_type := key.doc_type; last_id := key.doc_id; used := used + 1;
    END LOOP;
    UPDATE biz_doc_notice_pending_state
    SET backfill_after_type = last_type, backfill_after_id = last_id, backfill_complete = used < p_limit,
        ready = false, use_summary = false, update_time = clock_timestamp()
    WHERE tenant_key = state.tenant_key;
    RETURN QUERY SELECT used, used < p_limit, 0::BIGINT;
END;
$$;

CREATE FUNCTION scm_notice_pending_start_reconciliation() RETURNS void
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE state biz_doc_notice_pending_state;
BEGIN
    PERFORM scm_notice_pending_initialize();
    SELECT * INTO STRICT state FROM biz_doc_notice_pending_state
    WHERE tenant_key = scm_notice_pending_namespace() FOR UPDATE;
    IF NOT state.backfill_complete THEN RAISE EXCEPTION 'Notice backfill must complete before reconciliation'; END IF;
    UPDATE biz_doc_notice_pending_state
    SET reconcile_after_type = NULL, reconcile_after_id = NULL, reconcile_complete = false,
        reconcile_mismatches = 0, ready = false, use_summary = false, update_time = clock_timestamp()
    WHERE tenant_key = state.tenant_key;
END;
$$;

CREATE FUNCTION scm_notice_pending_reconcile(p_limit INTEGER)
RETURNS TABLE(processed INTEGER, complete BOOLEAN, mismatches BIGINT)
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE state biz_doc_notice_pending_state; key RECORD; locked biz_doc_notice_pending; actual RECORD;
        last_type VARCHAR(32); last_id BIGINT; used INTEGER := 0; differences BIGINT := 0;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 500 THEN
        RAISE EXCEPTION 'Notice maintenance batch must be within 1..500' USING ERRCODE = '22023';
    END IF;
    PERFORM scm_notice_pending_initialize();
    SELECT * INTO STRICT state FROM biz_doc_notice_pending_state
    WHERE tenant_key = scm_notice_pending_namespace() FOR UPDATE;
    IF NOT state.backfill_complete THEN RAISE EXCEPTION 'Notice backfill must complete before reconciliation'; END IF;
    IF state.reconcile_complete THEN
        RETURN QUERY SELECT 0, true, state.reconcile_mismatches; RETURN;
    END IF;
    last_type := state.reconcile_after_type; last_id := state.reconcile_after_id;
    FOR key IN SELECT * FROM scm_notice_pending_keys(state.tenant_key, last_type, last_id, p_limit)
    LOOP
        locked := scm_notice_pending_lock(state.tenant_key, key.doc_type, key.doc_id);
        -- 取锁之后独立读取；对账不悄悄修复差异，也不声称跨批次固定全库快照。
        SELECT * INTO STRICT actual FROM scm_notice_pending_actual(key.doc_type, key.doc_id);
        IF NOT locked.initialized OR (locked.pending_count, locked.latest_time, locked.latest_notice_id)
                IS DISTINCT FROM (actual.pending_count, actual.latest_time, actual.latest_notice_id) THEN
            differences := differences + 1;
        END IF;
        last_type := key.doc_type; last_id := key.doc_id; used := used + 1;
    END LOOP;
    differences := state.reconcile_mismatches + differences;
    UPDATE biz_doc_notice_pending_state
    SET reconcile_after_type = last_type, reconcile_after_id = last_id, reconcile_complete = used < p_limit,
        reconcile_mismatches = differences, ready = used < p_limit AND differences = 0,
        use_summary = false, update_time = clock_timestamp()
    WHERE tenant_key = state.tenant_key;
    RETURN QUERY SELECT used, used < p_limit, differences;
END;
$$;

CREATE FUNCTION scm_notice_pending_set_use_summary(p_enabled BOOLEAN) RETURNS void
LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS $$
DECLARE state biz_doc_notice_pending_state;
BEGIN
    IF p_enabled IS NULL THEN RAISE EXCEPTION 'Notice read mode must be explicit' USING ERRCODE = '22023'; END IF;
    PERFORM scm_notice_pending_initialize();
    SELECT * INTO STRICT state FROM biz_doc_notice_pending_state
    WHERE tenant_key = scm_notice_pending_namespace() FOR UPDATE;
    IF p_enabled AND NOT state.ready THEN RAISE EXCEPTION 'Notice summary is not ready'; END IF;
    UPDATE biz_doc_notice_pending_state SET use_summary = p_enabled, update_time = clock_timestamp()
    WHERE tenant_key = state.tenant_key;
END;
$$;
