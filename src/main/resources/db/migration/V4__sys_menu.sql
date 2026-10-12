-- V4：菜单表。菜单和按钮权限都存在这里：type 1 目录、2 菜单（一个页面）、3 按钮（页面上的一个操作）

CREATE TABLE sys_menu (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id   BIGINT       NOT NULL DEFAULT 0,       -- 上级菜单 id，0 表示顶层
    name        VARCHAR(64)  NOT NULL,                 -- 显示名，如 用户管理
    type        SMALLINT     NOT NULL,                 -- 1 目录 2 菜单 3 按钮
    path        VARCHAR(128),                          -- 路由地址（目录、菜单用），如 user
    component   VARCHAR(128),                          -- 前端页面组件（菜单用），如 system/user/index
    perms       VARCHAR(100),                          -- 权限标识（菜单、按钮用），如 system:user:add
    icon        VARCHAR(64),
    sort        INT          NOT NULL DEFAULT 0,       -- 同级之间的顺序，小的在前
    visible     BOOLEAN      NOT NULL DEFAULT TRUE,    -- false：有权限但不在侧边栏显示
    status      SMALLINT     NOT NULL DEFAULT 1,       -- 1 启用 0 停用
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_sys_menu_parent ON sys_menu (parent_id) WHERE deleted = false;
COMMENT ON TABLE sys_menu IS '菜单与按钮权限';

-- 初始菜单：系统管理目录 + 四个页面 + 每个页面的按钮。id 由数据库生成，所以用 CTE 把上一级的 id 传给下一级
WITH dir AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    VALUES (0, '系统管理', 1, '/system', 'Setting', 100)
    RETURNING id
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort)
    SELECT dir.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort
    FROM dir, (VALUES ('用户管理', 'user', 'system/user/index', 'system:user:list', 'User', 1),
                      ('角色管理', 'role', 'system/role/index', 'system:role:list', 'UserFilled', 2),
                      ('菜单管理', 'menu', 'system/menu/index', 'system:menu:list', 'Menu', 3),
                      ('字典管理', 'dict', 'system/dict/index', 'system:dict:list', 'Collection', 4))
         AS p(name, path, component, perms, icon, sort)
    RETURNING id, perms
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort)
SELECT pages.id, b.name, 3, b.perms, b.sort
FROM pages
JOIN (VALUES ('system:user:list', '新增', 'system:user:add', 1),
             ('system:user:list', '修改', 'system:user:edit', 2),
             ('system:user:list', '删除', 'system:user:remove', 3),
             ('system:user:list', '重置密码', 'system:user:resetPwd', 4),
             ('system:role:list', '新增', 'system:role:add', 1),
             ('system:role:list', '修改', 'system:role:edit', 2),
             ('system:role:list', '删除', 'system:role:remove', 3),
             ('system:menu:list', '新增', 'system:menu:add', 1),
             ('system:menu:list', '修改', 'system:menu:edit', 2),
             ('system:menu:list', '删除', 'system:menu:remove', 3),
             ('system:dict:list', '新增', 'system:dict:add', 1),
             ('system:dict:list', '修改', 'system:dict:edit', 2),
             ('system:dict:list', '删除', 'system:dict:remove', 3))
     AS b(page_perms, name, perms, sort)
  ON b.page_perms = pages.perms;
