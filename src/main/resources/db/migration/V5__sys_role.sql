-- V5：角色，以及两张关联表：用户-角色、角色-菜单（RBAC：用户 → 角色 → 菜单/按钮权限）

CREATE TABLE sys_role (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,                 -- 角色编码，如 admin、warehouse_keeper；admin 是超级管理员，拥有全部权限
    name        VARCHAR(64)  NOT NULL,                 -- 显示名，如 超级管理员
    sort        INT          NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,       -- 1 启用 0 停用；停用的角色不再给任何人权限
    remark      VARCHAR(255),
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX uk_sys_role_code ON sys_role (code) WHERE deleted = false;
COMMENT ON TABLE sys_role IS '角色';

-- 关联表只记"谁和谁有关系"：没有审计字段，也不逻辑删除，重新分配时整批删掉再插入
CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_sys_user_role_role ON sys_user_role (role_id);
COMMENT ON TABLE sys_user_role IS '用户-角色';

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);
CREATE INDEX idx_sys_role_menu_menu ON sys_role_menu (menu_id);
COMMENT ON TABLE sys_role_menu IS '角色-菜单';

-- 超级管理员角色。admin 用户和它的关联由启动时的 AdminInitializer 保证（生产库是空库，这里还没有 admin 用户）
INSERT INTO sys_role (code, name, sort, remark) VALUES ('admin', '超级管理员', 0, '拥有全部权限，不能修改和删除');
