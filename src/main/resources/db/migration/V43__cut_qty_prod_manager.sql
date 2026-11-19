-- RFC 013 §4（甲方 11-18 答 Q09）：排单、回货溢出 5%（程序里的常数），有裁床数按裁床数。
-- 外发加工单、成品采购单明细加裁床数：生产单详情"登记裁床数"按 SKU 填，空 = 没登记，回货上限、完成判定照旧按件数
ALTER TABLE prd_order_item ADD COLUMN cut_qty INT CHECK (cut_qty >= 0);
COMMENT ON COLUMN prd_order_item.cut_qty IS '裁床数：登记了的回货上限 = ⌊裁床数 × 1.05⌋、回够 97% 算完成；空 = 没登记，按件数';

-- RFC 013 §3（甲方 11-18 答 Q10）：一级审批，生产经理审。加一个角色模板"生产经理"，数据范围全部：
-- 生产跟单的权限，加上需求订单、订单变更、外发加工单和成品采购单、采购单、物料盘点单的审核（盘点单另给看）。
-- 已经有同编码的角色（甲方手建过）就不动它，授权照样加
INSERT INTO sys_role (code, name, sort, data_scope, remark)
VALUES ('prod_manager', '生产经理', 9, 'ALL', '预置模板，权限和数据范围可以改')
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT pm.id, rm.menu_id
FROM sys_role pm
JOIN sys_role mc ON mc.code = 'merchandiser' AND mc.deleted = false
JOIN sys_role_menu rm ON rm.role_id = mc.id
WHERE pm.code = 'prod_manager' AND pm.deleted = false
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.deleted = false AND m.admin_only = false
 AND m.perms IN ('order:order:audit', 'order:change:audit', 'production:order:audit', 'production:order:close',
                 'purchase:order:audit', 'inventory:count:list', 'inventory:count:export', 'inventory:count:audit')
WHERE r.code = 'prod_manager' AND r.deleted = false
ON CONFLICT DO NOTHING;

-- 授了按钮的，再授它的各级上级（同 V16 第 4 步）
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false AND r.code = 'prod_manager'
    JOIN sys_menu m ON m.id = rm.menu_id
    WHERE m.parent_id <> 0
    UNION
    SELECT up.role_id, m.parent_id
    FROM up
    JOIN sys_menu m ON m.id = up.menu_id
    WHERE m.parent_id <> 0
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, menu_id FROM up
ON CONFLICT DO NOTHING;
