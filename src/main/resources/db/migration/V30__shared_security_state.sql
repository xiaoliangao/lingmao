SET LOCAL lock_timeout = '5s';

CREATE TABLE sys_deployment_namespace (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    tenant_id VARCHAR(64) NOT NULL UNIQUE
);

-- Constant defaults are metadata-only on PostgreSQL 16. Never rewrite old migrations.
ALTER TABLE sys_user ADD COLUMN session_version BIGINT NOT NULL DEFAULT 0;
CREATE FUNCTION scm_bump_session_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.password IS DISTINCT FROM OLD.password OR NEW.status IS DISTINCT FROM OLD.status
       OR NEW.deleted IS DISTINCT FROM OLD.deleted THEN
        NEW.session_version := OLD.session_version + 1;
    ELSE
        NEW.session_version := OLD.session_version;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sys_user_session_version BEFORE UPDATE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION scm_bump_session_version();

CREATE TABLE sys_login_session (
    tenant_id VARCHAR(64) NOT NULL,
    jti VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    session_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, jti)
);
CREATE INDEX idx_login_session_expiry ON sys_login_session(expires_at);
CREATE INDEX idx_login_session_user ON sys_login_session(user_id);

CREATE TABLE sys_refresh_session (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES sys_user(id),
    session_version BIGINT NOT NULL,
    browser_hash VARCHAR(64) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    rotation_nonce VARCHAR(43) NOT NULL,
    access_jti UUID NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    previous_hash VARCHAR(64),
    grace_until TIMESTAMPTZ,
    grace_replays SMALLINT NOT NULL DEFAULT 0 CHECK (grace_replays BETWEEN 0 AND 2),
    revoked BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX idx_refresh_previous ON sys_refresh_session(previous_hash) WHERE previous_hash IS NOT NULL;
CREATE INDEX idx_refresh_user ON sys_refresh_session(user_id);
CREATE INDEX idx_refresh_expiry ON sys_refresh_session(expires_at);

-- New field permissions are independent of the cost permission.
INSERT INTO sys_menu(parent_id, name, type, perms, sort)
SELECT p.id, v.name, 3, v.perms, v.sort
FROM (VALUES ('/suppliers','看联系方式','supplier:supplier:contact',98),
             ('/orders','看销售报价','order:price:query',98),
             ('/production','看生产成交价','production:price:query',98)) v(path,name,perms,sort)
JOIN sys_menu p ON p.path=v.path
WHERE NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.perms=v.perms AND NOT m.deleted);

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT r.id, m.id FROM sys_role r JOIN sys_menu m
  ON (m.perms='supplier:supplier:contact' AND r.code IN ('buyer','finance','manager'))
  OR (m.perms='order:price:query' AND r.code IN ('sales','finance','manager'))
  OR (m.perms='production:price:query' AND r.code IN ('merchandiser','buyer','finance','manager'))
WHERE NOT r.deleted AND NOT m.deleted
ON CONFLICT DO NOTHING;
