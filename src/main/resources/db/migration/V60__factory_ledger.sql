-- V60：工厂料账（RFC 015，PRD 07.09、08.07；甲方 11-27 答 Q55：应耗按良品，超耗不扣钱、记下来）。
-- 不加表：耗料核销单是物料出库单的一种（inv_doc.doc_type = 'CONSUME_OUT'，VARCHAR(16)、没有 CHECK，不用改列）

-- 1. 一张生产单同时只有一张没作废的耗料核销单（两个人同时开，第二张被挡住）
CREATE UNIQUE INDEX uk_inv_doc_consume ON inv_doc (source_id)
    WHERE doc_type = 'CONSUME_OUT' AND status <> 'VOIDED';

COMMENT ON COLUMN inv_doc_item.over_reason IS '超领原因（领料出库）/ 超耗原因（耗料核销）：超过建议数时必填';

-- 2. 菜单：生产 › 工厂料账（看板前面）。已经有就不建；"生产"目录找不到时不建，管理员在菜单管理里补
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/make' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '工厂料账', 2, '/factory-ledger', 'production/ledger/index', 'production:ledger:list', 'Notebook', 25, true, false
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:ledger:list' AND deleted = false);

INSERT INTO sys_menu (parent_id, name, type, perms, sort, visible, admin_only)
SELECT m.id, '导出', 3, 'production:ledger:export', 1, true, false
FROM sys_menu m
WHERE m.perms = 'production:ledger:list' AND m.deleted = false
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:ledger:export' AND deleted = false);

-- 3. 授权（RFC 015 §11 第 7 条）：生产经理、生产跟单、仓管、管理层、财务，加上理单（跟工厂对账，甲方 11-27 答 Q14）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM sys_role r
JOIN sys_menu m ON m.perms IN ('production:ledger:list', 'production:ledger:export') AND m.deleted = false
WHERE r.deleted = false
  AND r.code IN ('prod_manager', 'merchandiser', 'warehouse', 'manager', 'finance', 'order_clerk')
ON CONFLICT DO NOTHING;

-- 上级目录"生产"：仓管、财务原来可能没有
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, m.parent_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.perms = 'production:ledger:list' AND m.deleted = false AND m.parent_id <> 0
ON CONFLICT DO NOTHING;
