-- 订单进度总览（RFC 016 §1，PRD §6.5.7 的 06.07）：订单 › 进度总览，一行是一张在手订单里的一个款。
-- 只读页面，没有按钮权限。凡是能看需求订单的角色模板都给（生产经理、业务主管也有需求订单的查看）

WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/sales' AND deleted = false
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '进度总览', 2, '/order-progress', 'order/progress/index', 'order:progress:list', 'TrendCharts', 30, true, false
FROM dir;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.perms = 'order:progress:list' AND m.deleted = false
WHERE r.deleted = false
  AND r.code IN ('dev', 'dev_lead', 'sales', 'sales_lead', 'merchandiser', 'buyer', 'finance', 'manager', 'prod_manager')
ON CONFLICT DO NOTHING;

-- 上级目录"订单"：这几个角色本来就有（有需求订单），照 V46 再补一遍，防止有人手工去掉过需求订单
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, m.parent_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.perms = 'order:progress:list'
ON CONFLICT DO NOTHING;
