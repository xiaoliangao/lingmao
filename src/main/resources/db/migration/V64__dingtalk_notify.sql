-- V64：钉钉推送和手机待办（甲方 11-27 答 Q65，RFC 019 §4）。
-- 1. 后台任务多一种：发一条钉钉工作通知。沿用 V32 的 sys_outbox（和业务同一个事务入队、租约、失败重试、5 次进死信）：
--    钉钉消息只是通知，不用外部回执、单号映射；架构 §14.3 的 biz_outbox 留给三期往聚水潭推单据时再建
ALTER TABLE sys_outbox DROP CONSTRAINT sys_outbox_kind_check;
ALTER TABLE sys_outbox ADD CONSTRAINT sys_outbox_kind_check
    CHECK (kind IN ('CACHE_INVALIDATE', 'STOCK_LOG_EXPORT', 'DINGTALK_MESSAGE'));
-- 管理后台"消息推送"按种类、时间倒序列最近的消息
CREATE INDEX idx_sys_outbox_kind_created ON sys_outbox (tenant_key, kind, created_at DESC);

-- 2. 用户在钉钉里的 userid：第一次发消息时按手机号去钉钉查，查到记下；dingtalk_mobile 是查的时候用的手机号，换了手机号重新查
ALTER TABLE sys_user ADD COLUMN dingtalk_user_id VARCHAR(64);
ALTER TABLE sys_user ADD COLUMN dingtalk_mobile VARCHAR(20);
COMMENT ON COLUMN sys_user.dingtalk_user_id IS '钉钉 userid，按手机号查到后缓存';
COMMENT ON COLUMN sys_user.dingtalk_mobile IS '查 dingtalk_user_id 时用的手机号；和 phone 不一样就重新查';

-- 3. 推哪几种消息：甲方选了"单据被驳回""货期预警"。没有这一行按这两种
INSERT INTO sys_config (config_key, config_value, remark)
VALUES ('notify.events', '["DOC_REJECTED", "DUE_WARNING"]'::jsonb, '钉钉推哪几种消息：DOC_REJECTED 单据被驳回、DUE_WARNING 货期预警（每天一条）')
ON CONFLICT (config_key) DO NOTHING;

-- 4. 管理后台 › 系统管理 加"消息推送"：钉钉配好没有、推哪几种、最近发的消息。只给超级管理员（admin_only，接口另用 @superAdmin 兜底）
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/system' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '消息推送', 2, 'notify', 'system/notify/index', 'system:notify:edit', 'ChatDotRound', 9, true, true
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'system:notify:edit' AND deleted = false);

-- 5. 报表目录加"手机待办"（单独成页，web/src/router/standalone.ts）：待我审核（能直接审）和预警，钉钉消息点开就到这里。
--    审核还是走各单据自己的接口和权限，这一页只是入口，所以给各业务岗位（和预警中心一样；"物料单审核"是叠在别的角色上的小模板，不给）
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/report' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '手机待办', 2, '/mobile-todo', 'report/mobile/index', 'report:mobile:list', 'Iphone', 60, true, false
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'report:mobile:list' AND deleted = false);

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_menu m
JOIN sys_role r ON r.code = ANY(ARRAY['dev', 'dev_lead', 'sales', 'sales_lead', 'merchandiser', 'buyer', 'warehouse', 'finance',
                                      'manager', 'prod_manager', 'order_clerk']) AND r.deleted = false
WHERE m.perms = 'report:mobile:list' AND m.deleted = false
ON CONFLICT DO NOTHING;

-- 上级目录（报表）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, m.parent_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.perms = 'report:mobile:list' AND m.deleted = false
WHERE m.parent_id <> 0
ON CONFLICT DO NOTHING;
