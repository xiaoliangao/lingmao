-- V57：部门树、首次登录改密码、编码规则（RFC 018）。

-- 1. 部门：一棵树，人和部门多对多（领猫里一个人可以挂几个部门）。新表不用逻辑删除：
--    有下级、有人的删不掉（外键拦，Service 先查好报清楚）
CREATE TABLE sys_dept (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id   BIGINT       REFERENCES sys_dept (id),   -- 上级，顶层为空；最多 5 级（Service 校验）
    name        VARCHAR(32)  NOT NULL,
    sort        INT          NOT NULL DEFAULT 0,         -- 同级顺序
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 同级不重名：顶层的 parent_id 是 NULL，按 0 算
CREATE UNIQUE INDEX uk_sys_dept_name ON sys_dept (COALESCE(parent_id, 0), name);
CREATE INDEX idx_sys_dept_parent ON sys_dept (parent_id);
COMMENT ON TABLE sys_dept IS '部门（RFC 018 §1）：树；人和部门的关系在 sys_user_dept';

CREATE TABLE sys_user_dept (
    user_id BIGINT NOT NULL REFERENCES sys_user (id),
    dept_id BIGINT NOT NULL REFERENCES sys_dept (id),
    PRIMARY KEY (user_id, dept_id)
);
CREATE INDEX idx_sys_user_dept_dept ON sys_user_dept (dept_id);
COMMENT ON TABLE sys_user_dept IS '人在哪几个部门（RFC 018 §1）；删用户（逻辑删除）时一起删掉';

-- 原来自由填的部门：按名字建成顶层部门（名字截到 32 个字），挂上没删的人；列留着不再用
INSERT INTO sys_dept (name)
SELECT DISTINCT left(btrim(dept), 32) FROM sys_user
WHERE deleted = false AND dept IS NOT NULL AND btrim(dept) <> '';
INSERT INTO sys_user_dept (user_id, dept_id)
SELECT u.id, d.id FROM sys_user u
JOIN sys_dept d ON d.parent_id IS NULL AND d.name = left(btrim(u.dept), 32)
WHERE u.deleted = false;
COMMENT ON COLUMN sys_user.dept IS 'V57 起不用，部门看 sys_user_dept（RFC 018 §1.2）';

-- 2. 首次登录改密码（RFC 018 §2）：导入建的、管理员新建的、管理员重置密码的置 true，本人改了密码置 false。
--    已有的账号不动
ALTER TABLE sys_user ADD COLUMN pwd_must_change BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN sys_user.pwd_must_change IS '要先改密码：true 时登录拿到的令牌只能看自己、改密码、退出（RFC 018 §2）';

-- 3. 品类编码：选填，编码规则里的"品类编码"一段用它（RFC 018 §4.4）。不要求唯一：不同上级下可能一样
ALTER TABLE bas_category ADD COLUMN code VARCHAR(8);
COMMENT ON COLUMN bas_category.code IS '品类编码，选填，大写字母和数字；款号规则里有"品类编码"一段时用（RFC 018）';

-- 4. 编码规则（RFC 018 §4）：款号、物料编码、供应商编码各一条通用规则，可以再按大类 / 物料类型加规则。
--    scope_values 存字典值（款号是大类 style_class，物料是物料类型 material_type），和别的字典列一样不建外键
CREATE TABLE sys_code_rule (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target       VARCHAR(16)   NOT NULL,                 -- STYLE 款号 / MATERIAL 物料编码 / SUPPLIER 供应商编码
    name         VARCHAR(32)   NOT NULL,
    scope_values VARCHAR(64)[] NOT NULL DEFAULT '{}',    -- 空数组 = 通用规则
    segments     JSONB         NOT NULL,                 -- 段，按顺序；格式见 CodeSegment
    sort         INT           NOT NULL DEFAULT 0,
    create_by    BIGINT,
    create_time  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by    BIGINT,
    update_time  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_sys_code_rule_target CHECK (target IN ('STYLE', 'MATERIAL', 'SUPPLIER'))
);
-- 每种编码只有一条通用规则
CREATE UNIQUE INDEX uk_sys_code_rule_general ON sys_code_rule (target) WHERE scope_values = '{}';
CREATE INDEX idx_sys_code_rule_target ON sys_code_rule (target, sort);
COMMENT ON TABLE sys_code_rule IS '编码规则（RFC 018 §4）：每条是几段拼起来；只管以后新编的，已有的编码不变';

-- 预置的三条通用规则和改之前写死的格式一样：装上新版编出来的号不变，流水接着往下编（RFC 018 §4.5）
INSERT INTO sys_code_rule (target, name, segments, sort) VALUES
    ('STYLE', '通用规则',
     '[{"type":"STYLE_CLASS"},{"type":"DATE","format":"yyMMdd"},{"type":"DESIGNER"},{"type":"SEQ","width":2}]', 0),
    ('MATERIAL', '通用规则',
     '[{"type":"MAT_CATEGORY"},{"type":"SEQ","width":4}]', 0),
    ('SUPPLIER', '通用规则',
     '[{"type":"TEXT","text":"GYS"},{"type":"SEQ","width":4}]', 0);

-- 5. 菜单：系统管理下加"编码规则"一页（只给超级管理员，接口另用 @superAdmin 兜底）；用户管理加"导入"按钮。
--    已经有了不再建；"系统管理"目录被改过路径、找不到时不建，管理员在菜单管理里补
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/system' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '编码规则', 2, 'code-rule', 'system/code-rule/index', 'system:code-rule:list', 'Tickets', 8, true, true
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:code-rule:list' AND deleted = false);

WITH page AS (
    SELECT id FROM sys_menu WHERE perms = 'system:user:list' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT page.id, '导入', 3, NULL, NULL, 'system:user:import', NULL, 5, true, true
FROM page
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:user:import' AND deleted = false);
