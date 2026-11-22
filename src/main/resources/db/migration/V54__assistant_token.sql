-- V54：智能助手的底座（RFC 017 §3.1、§13 第①步）。
-- 1. 登录会话加两列，给助手令牌用：
--    scope：NULL 是普通登录；'ASSISTANT' 是用户打开助手时换来的短期只读令牌（30 分钟、不能刷新）。
--           过滤器按这一列认，不只看令牌里的声明。
--    parent_jti：助手令牌是从哪个登录令牌换来的；那个登录令牌退出时，换来的助手令牌一起删掉。
--    改密、停用、删除照旧靠 session_version，助手令牌和登录令牌一起失效。
ALTER TABLE sys_login_session ADD COLUMN scope VARCHAR(16);
ALTER TABLE sys_login_session ADD COLUMN parent_jti VARCHAR(64);
CREATE INDEX idx_login_session_parent ON sys_login_session(tenant_id, parent_jti) WHERE parent_jti IS NOT NULL;

-- 2. 权限"使用智能助手"（assistant:chat:use）：助手是右侧抽屉，不是页面，所以是 sys_menu 顶层的一个按钮
--    （type = 3，侧边栏只取目录和页面、不显示它；角色的权限树里能勾）。
--    预置的业务角色都给：AI 统一开关默认关，装好了也用不了；谁能用由管理员在角色里调
INSERT INTO sys_menu (parent_id, name, type, perms, sort, visible, admin_only)
SELECT 0, '使用智能助手', 3, 'assistant:chat:use', 90, true, false
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'assistant:chat:use' AND deleted = false);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.perms = 'assistant:chat:use' AND m.deleted = false
WHERE r.deleted = false
  AND r.code IN ('dev', 'dev_lead', 'sales', 'sales_lead', 'merchandiser', 'buyer', 'warehouse', 'finance', 'manager', 'prod_manager')
ON CONFLICT DO NOTHING;
