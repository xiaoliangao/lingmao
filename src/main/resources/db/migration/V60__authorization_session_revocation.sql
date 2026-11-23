SET LOCAL lock_timeout = '5s';

-- 授权配置低频写入，共享一个短事务锁。一次“整体替换”有多条 SQL，
-- 只在每条 SQL 内排序用户锁仍可能在后一句发现新成员并倒序锁人。
-- API 必须在任何角色/用户/菜单行锁之前调用；直接 SQL 也由下面 BEFORE STATEMENT 统一进入。
-- 普通用户属性、密码、访问/刷新和业务单据均不获取此锁。
CREATE FUNCTION scm_lock_authorization_graph() RETURNS void
LANGUAGE SQL SET search_path = pg_catalog, public AS $$
    SELECT pg_advisory_xact_lock(7302, 60)
$$;

CREATE FUNCTION scm_lock_authorization_write() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    PERFORM scm_lock_authorization_graph();
    RETURN NULL;
END;
$$;
CREATE TRIGGER authorization_write_lock BEFORE INSERT OR UPDATE OR DELETE ON sys_role
    FOR EACH STATEMENT EXECUTE FUNCTION scm_lock_authorization_write();
CREATE TRIGGER authorization_write_lock BEFORE INSERT OR UPDATE OR DELETE ON sys_menu
    FOR EACH STATEMENT EXECUTE FUNCTION scm_lock_authorization_write();
CREATE TRIGGER authorization_write_lock BEFORE INSERT OR UPDATE OR DELETE ON sys_user_role
    FOR EACH STATEMENT EXECUTE FUNCTION scm_lock_authorization_write();
CREATE TRIGGER authorization_write_lock BEFORE INSERT OR UPDATE OR DELETE ON sys_role_menu
    FOR EACH STATEMENT EXECUTE FUNCTION scm_lock_authorization_write();

-- V30 只允许密码/状态/删除触发递增，并会吞掉授权变更显式发出的 +1。
-- 保留原凭据规则，另外只接受精确的原子 +1，禁止旧版本写回或任意重置版本。
CREATE OR REPLACE FUNCTION scm_bump_session_version() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF NEW.password IS DISTINCT FROM OLD.password OR NEW.status IS DISTINCT FROM OLD.status
       OR NEW.deleted IS DISTINCT FROM OLD.deleted THEN
        NEW.session_version := OLD.session_version + 1;
    ELSIF NEW.session_version IS DISTINCT FROM OLD.session_version
          AND (NEW.session_version IS NULL OR NEW.session_version <> OLD.session_version + 1) THEN
        RAISE EXCEPTION 'session version must advance by one' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- 版本与授权在同一数据库事务里提交/回滚。访问和刷新校验都比较此版本，
-- 不再反向锁刷新会话行，避免与 refresh 的“会话族 -> 用户 FOR SHARE”发生锁环。
-- 同一语句涉及多个用户时先按 ID 取得 NO KEY UPDATE 锁；它不阻挡关联表的 FK KEY SHARE。
CREATE FUNCTION scm_revoke_user_sessions(user_ids BIGINT[]) RETURNS void
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
BEGIN
    IF user_ids IS NULL OR cardinality(user_ids) = 0 THEN RETURN; END IF;
    PERFORM id FROM sys_user WHERE id = ANY(user_ids) ORDER BY id FOR NO KEY UPDATE;
    UPDATE sys_user SET session_version = session_version + 1 WHERE id = ANY(user_ids);
END;
$$;

-- 关系的真实变更集，而不是整个表；UPDATE 同值不踢人。按语句去重，批量关系也不重复处理用户。
CREATE FUNCTION scm_revoke_user_role_sessions() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE affected BIGINT[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT array_agg(DISTINCT user_id) INTO affected FROM new_grants;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT array_agg(DISTINCT user_id) INTO affected FROM old_grants;
    ELSE
        SELECT array_agg(DISTINCT user_id) INTO affected FROM (
            (SELECT user_id, role_id FROM old_grants EXCEPT SELECT user_id, role_id FROM new_grants)
            UNION
            (SELECT user_id, role_id FROM new_grants EXCEPT SELECT user_id, role_id FROM old_grants)
        ) changed;
    END IF;
    PERFORM scm_revoke_user_sessions(affected);
    RETURN NULL;
END;
$$;
CREATE TRIGGER authorization_sessions_insert AFTER INSERT ON sys_user_role
    REFERENCING NEW TABLE AS new_grants FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_user_role_sessions();
CREATE TRIGGER authorization_sessions_delete AFTER DELETE ON sys_user_role
    REFERENCING OLD TABLE AS old_grants FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_user_role_sessions();
CREATE TRIGGER authorization_sessions_update AFTER UPDATE ON sys_user_role
    REFERENCING OLD TABLE AS old_grants NEW TABLE AS new_grants
    FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_user_role_sessions();

CREATE FUNCTION scm_revoke_role_menu_sessions() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE roles BIGINT[]; affected BIGINT[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT array_agg(DISTINCT role_id) INTO roles FROM new_grants;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT array_agg(DISTINCT role_id) INTO roles FROM old_grants;
    ELSE
        SELECT array_agg(DISTINCT role_id) INTO roles FROM (
            (SELECT role_id, menu_id FROM old_grants EXCEPT SELECT role_id, menu_id FROM new_grants)
            UNION
            (SELECT role_id, menu_id FROM new_grants EXCEPT SELECT role_id, menu_id FROM old_grants)
        ) changed;
    END IF;
    SELECT array_agg(DISTINCT ur.user_id) INTO affected FROM sys_user_role ur
    JOIN sys_role r ON r.id = ur.role_id
    WHERE ur.role_id = ANY(roles) AND r.status = 1 AND NOT r.deleted;
    PERFORM scm_revoke_user_sessions(affected);
    RETURN NULL;
END;
$$;
CREATE TRIGGER authorization_sessions_insert AFTER INSERT ON sys_role_menu
    REFERENCING NEW TABLE AS new_grants FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_menu_sessions();
CREATE TRIGGER authorization_sessions_delete AFTER DELETE ON sys_role_menu
    REFERENCING OLD TABLE AS old_grants FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_menu_sessions();
CREATE TRIGGER authorization_sessions_update AFTER UPDATE ON sys_role_menu
    REFERENCING OLD TABLE AS old_grants NEW TABLE AS new_grants
    FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_menu_sessions();

CREATE FUNCTION scm_revoke_role_sessions() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE affected BIGINT[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT array_agg(DISTINCT ur.user_id) INTO affected
        FROM new_roles n JOIN sys_user_role ur ON ur.role_id = n.id;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT array_agg(DISTINCT ur.user_id) INTO affected
        FROM old_roles o JOIN sys_user_role ur ON ur.role_id = o.id;
    ELSE
        SELECT array_agg(DISTINCT ur.user_id) INTO affected
        FROM old_roles o JOIN new_roles n ON n.id = o.id JOIN sys_user_role ur ON ur.role_id = n.id
        WHERE (o.code, o.status, o.deleted, o.data_scope)
              IS DISTINCT FROM (n.code, n.status, n.deleted, n.data_scope);
    END IF;
    PERFORM scm_revoke_user_sessions(affected);
    RETURN NULL;
END;
$$;
CREATE TRIGGER authorization_sessions_insert AFTER INSERT ON sys_role
    REFERENCING NEW TABLE AS new_roles FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_sessions();
CREATE TRIGGER authorization_sessions_delete AFTER DELETE ON sys_role
    REFERENCING OLD TABLE AS old_roles FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_sessions();
CREATE TRIGGER authorization_sessions_update AFTER UPDATE ON sys_role
    REFERENCING OLD TABLE AS old_roles NEW TABLE AS new_roles
    FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_role_sessions();

CREATE FUNCTION scm_revoke_menu_sessions() RETURNS trigger
LANGUAGE plpgsql SET search_path = pg_catalog, public AS $$
DECLARE menus BIGINT[]; affected BIGINT[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        SELECT array_agg(id) INTO menus FROM new_menus;
    ELSIF TG_OP = 'DELETE' THEN
        SELECT array_agg(id) INTO menus FROM old_menus;
    ELSE
        SELECT array_agg(n.id) INTO menus FROM old_menus o JOIN new_menus n USING(id)
        WHERE (o.parent_id, o.status, o.deleted, o.admin_only, o.perms, o.type)
              IS DISTINCT FROM (n.parent_id, n.status, n.deleted, n.admin_only, n.perms, n.type);
    END IF;
    IF menus IS NULL THEN RETURN NULL; END IF;
    -- 父菜单停用/移动时，只有后代被分配给角色的用户也必须撤销。
    -- UNION 去重终止历史坏数据中的环；管理员没有 role_menu 关联，单独纳入隐式授权。
    WITH RECURSIVE subtree(id) AS (
        SELECT unnest(menus)
        UNION
        SELECT m.id FROM sys_menu m JOIN subtree p ON m.parent_id = p.id
    )
    SELECT array_agg(DISTINCT ur.user_id) INTO affected FROM sys_user_role ur
    JOIN sys_role r ON r.id = ur.role_id
    WHERE r.status = 1 AND NOT r.deleted AND (r.code = 'admin' OR EXISTS (
        SELECT 1 FROM sys_role_menu rm JOIN subtree m ON m.id = rm.menu_id WHERE rm.role_id = r.id
    ));
    PERFORM scm_revoke_user_sessions(affected);
    RETURN NULL;
END;
$$;
CREATE TRIGGER authorization_sessions_insert AFTER INSERT ON sys_menu
    REFERENCING NEW TABLE AS new_menus FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_menu_sessions();
CREATE TRIGGER authorization_sessions_delete AFTER DELETE ON sys_menu
    REFERENCING OLD TABLE AS old_menus FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_menu_sessions();
CREATE TRIGGER authorization_sessions_update AFTER UPDATE ON sys_menu
    REFERENCING OLD TABLE AS old_menus NEW TABLE AS new_menus
    FOR EACH STATEMENT EXECUTE FUNCTION scm_revoke_menu_sessions();
