-- V52：AI 功能统一开关（RFC 017 §3.7）。
-- 1. sys_config 的 ai.switch，默认关。没有这一行、读出错，程序都按关处理（AiSwitchService），这一行只是让设置页一打开就有值。
--    以后每一档 AI 功能做的时候，在这个值里加自己的分开关（features.xxx），这里不提前写
INSERT INTO sys_config (config_key, config_value, remark)
VALUES ('ai.switch', '{"enabled": false}'::jsonb, 'AI 功能统一开关：助手、识别预填、企查查 agent、后台归因都看它；没有这一行等于关')
ON CONFLICT (config_key) DO NOTHING;

-- 2. 管理后台 › 系统管理 加"AI 功能"一页，只给超级管理员（admin_only，和审批规则一样；接口另用 @superAdmin 兜底）。
--    已经有这个菜单就不再建；"系统管理"目录被改过路径、找不到时不建，管理员在菜单管理里补
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/system' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, 'AI 功能', 2, 'ai-switch', 'system/ai-switch/index', 'system:ai:switch', 'MagicStick', 7, true, true
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:ai:switch' AND deleted = false);
