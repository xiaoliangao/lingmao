-- V59：甲方 11-27 的答复（PRD v0.57 §3.1、Q14、Q56）。
-- 只加预置模板的授权和字典项；管理员在角色、字典里改过的不碰：已有同编码的角色不动它，授权、字典项只补不删。

-- 1. 新角色模板"理单"（Q14 第 3 问）：理单的人下外发加工单、成品采购单，安排工厂的辅料采购，跟工厂对账，
--    建物料、做物料入库单和出库单。= 生产跟单 + 采购 的权限，加物料入库单、出库单的新建、编辑、删除；
--    不给看银行全号（对账、付款是财务的事），不给审核（物料单谁审见下面的"物料单审核"）。数据范围本人，和生产跟单、采购一样
INSERT INTO sys_role (code, name, sort, data_scope, remark)
VALUES ('order_clerk', '理单', 11, 'SELF', '预置模板，权限和数据范围可以改')
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT oc.id, rm.menu_id
FROM sys_role oc
JOIN sys_role src ON src.code IN ('merchandiser', 'buyer') AND src.deleted = false
JOIN sys_role_menu rm ON rm.role_id = src.id
JOIN sys_menu m ON m.id = rm.menu_id AND m.perms IS DISTINCT FROM 'supplier:supplier:bank'
WHERE oc.code = 'order_clerk' AND oc.deleted = false
ON CONFLICT DO NOTHING;

-- 2. 新角色模板"物料单审核"（Q14 第 2 问）：甲方点了名的几个人审物料入库单、出库单和盘点单，
--    按人给，所以单拆一个小模板，在用户页勾给这几个人，和他的"理单"一起用。
--    数据范围本人、勾"审核不受数据范围限制"：别人建的待审单也到他的"待我审核"里（PRD §3.2）；自己建的照样不能自己审
INSERT INTO sys_role (code, name, sort, data_scope, audit_any_scope, remark)
VALUES ('stock_auditor', '物料单审核', 12, 'SELF', true, '预置模板：物料入库单、出库单、盘点单的审核，和别的角色一起勾给审核的人')
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

-- 3. 各模板补的授权：
--    理单：物料入库单、出库单的新建、编辑、删除（上面从生产跟单、采购带过来的只有看）；
--    物料单审核：看物料入库单、出库单、盘点单、库存，审物料单和盘点单；
--    业务主管：审需求订单、订单变更（Q14 第 2 问：商品计划审）；
--    生产经理：供应商准入、关键信息改动的审批（Q14 第 2 问：两位生产经理审；原来只有管理层）；
--    仓管：审盘点单（Q14 第 2 问："仓库人员"也审）
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('order_clerk',   'inventory:doc:add'),
             ('order_clerk',   'inventory:doc:edit'),
             ('order_clerk',   'inventory:doc:remove'),
             ('stock_auditor', 'inventory:doc:list'),
             ('stock_auditor', 'inventory:doc:export'),
             ('stock_auditor', 'inventory:doc:audit'),
             ('stock_auditor', 'inventory:stock:list'),
             ('stock_auditor', 'inventory:stock:export'),
             ('stock_auditor', 'inventory:count:list'),
             ('stock_auditor', 'inventory:count:export'),
             ('stock_auditor', 'inventory:count:audit'),
             ('sales_lead',    'order:order:audit'),
             ('sales_lead',    'order:change:audit'),
             ('prod_manager',  'supplier:supplier:admit'),
             ('warehouse',     'inventory:count:audit')) AS g(code, perm)
JOIN sys_role r ON r.code = g.code AND r.deleted = false
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false AND m.admin_only = false
ON CONFLICT DO NOTHING;

-- 授了按钮的，再授它的各级上级（同 V16 第 4 步），只算这一次动过的角色
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false
     AND r.code IN ('order_clerk', 'stock_auditor', 'sales_lead', 'prod_manager', 'warehouse')
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

-- 4. 执行标准补 4 个（Q56）：甲方给的洗唛下单表里在用、字典里还没有的。值就是标准号，和 V11 的一样
INSERT INTO sys_dict_data (type_code, label, value, sort)
SELECT 'exec_standard', v.label, v.value, v.sort
FROM (VALUES ('GB/T 2666-2017 西裤',             'GB/T 2666-2017',  6),
             ('FZ/T 81007-2022 单、夹服装',      'FZ/T 81007-2022', 7),
             ('GB/T 14272-2021 羽绒服装',        'GB/T 14272-2021', 8),
             ('FZ/T 73022-2019 针织保暖内衣',    'FZ/T 73022-2019', 9)) AS v(label, value, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d
                  WHERE d.type_code = 'exec_standard' AND d.value = v.value AND d.deleted = false);
