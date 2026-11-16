-- V17：第 3、4 批的菜单、按钮权限码和预置角色的授权（docs/api 各模块文档的"页面"一节，README §5）。
-- 用户 11-15 定第 3、4 批前端先做：菜单先建好，页面文件还没做的显示"还没上线"；后端接口随后照 docs/api 实现。
-- 写法照 V16：目录 → 页面 → 按钮，再按 PRD §3.1 的表给八个预置角色授权，最后补上级目录。

-- 1. 新目录（sort 照 V16 留的空：订单 20、物料 30、生产 40）和各目录下的页面、按钮。
--    已有的目录（开发 /dev、管理后台的系统管理 /system）按 path 找。
--    "资料包"不是页面（在款式详情的页签里），照 V16 的"价格档案"建成 visible = false、没有组件的菜单，只让它的按钮在角色页里有地方勾。
--    外发加工单、成品采购单是一张表两种单据（架构 §4.10），两个菜单用同一个页面文件、同一套权限码，按钮只挂在外发加工单下面；
--    物料入库单、出库单同理，按钮只挂在入库单下面（角色页里不出现两遍）。
--    审批规则在管理后台，只给超级管理员（架构 §4.8），admin_only 和系统管理下的其他页面一样
WITH new_dirs AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    VALUES (0, '订单', 1, '/sales',  'Tickets', 20),
           (0, '物料', 1, '/supply', 'Van',     30),
           (0, '生产', 1, '/make',   'SetUp',   40)
    RETURNING id, path
), dirs AS (
    SELECT id, path FROM new_dirs
    UNION ALL
    SELECT id, path FROM sys_menu WHERE parent_id = 0 AND path IN ('/dev', '/system') AND deleted = false
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
    SELECT dirs.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort, p.visible, p.admin_only
    FROM dirs
    JOIN (VALUES ('/dev',    '核价',       '/costing',        'techpack/costing/index',   'techpack:cost:query',     'Money',        40, true,  false),
                 ('/dev',    '工艺标准',   '/craft-standard', 'techpack/standard/index',  'techpack:standard:list',  'Notebook',     50, true,  false),
                 ('/dev',    '资料包',     NULL,              NULL,                       NULL,                      'Document',     90, false, false),
                 ('/sales',  '需求订单',   '/orders',         'order/order/index',        'order:order:list',        'Document',     10, true,  false),
                 ('/sales',  '变更',       '/order-changes',  'order/change/index',       'order:change:list',       'Switch',       20, true,  false),
                 ('/supply', '算料',       '/mrp',            'purchase/mrp/index',       'purchase:mrp:list',       'DataAnalysis', 10, true,  false),
                 ('/supply', '采购',       '/purchases',      'purchase/order/index',     'purchase:order:list',     'ShoppingCart', 20, true,  false),
                 ('/supply', '物料入库单', '/stock-in',       'inventory/stock-in/index', 'inventory:doc:list',      'Download',     30, true,  false),
                 ('/supply', '物料出库单', '/stock-out',      'inventory/stock-out/index','inventory:doc:list',      'Upload',       40, true,  false),
                 ('/supply', '物料盘点单', '/stock-takes',    'inventory/count/index',    'inventory:count:list',    'Checked',      50, true,  false),
                 ('/supply', '库存',       '/stock',          'inventory/stock/index',    'inventory:stock:list',    'House',        60, true,  false),
                 ('/make',   '外发加工单', '/production',     'production/order/index',   'production:order:list',   'Scissor',      10, true,  false),
                 ('/make',   '成品采购单', '/fp-purchases',   'production/order/index',   'production:order:list',   'ShoppingBag',  20, true,  false),
                 ('/make',   '看板',       '/board',          'production/board/index',   'production:board:list',   'DataBoard',    30, true,  false),
                 ('/make',   '手机报工',   '/mobile',         'production/mobile/index',  'production:mobile:list',  'Iphone',       40, true,  false),
                 ('/system', '审批规则',   'approval-rule',   'system/approval-rule/index', 'biz:approval-rule:list', 'Stamp',      6,  true,  true))
         AS p(dir_path, name, path, component, perms, icon, sort, visible, admin_only)
      ON p.dir_path = dirs.path
    RETURNING id, name, admin_only
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT pages.id, b.name, 3, b.perms, b.sort, pages.admin_only
FROM pages
JOIN (VALUES ('资料包',     '查看',               'techpack:pack:list',        1),   -- 款式详情的资料包页签、导出 PDF（不含价格）
             ('资料包',     '新建',               'techpack:pack:add',         2),   -- 建研发 / 生产 BOM、修订、转生产 BOM
             ('资料包',     '编辑',               'techpack:pack:edit',        3),
             ('资料包',     '删除',               'techpack:pack:remove',      4),
             ('资料包',     '生效',               'techpack:pack:publish',     5),   -- PRD 里的"审：资料包生效"
             ('工艺标准',   '修改',               'techpack:standard:edit',    1),
             ('需求订单',   '新建',               'order:order:add',           1),
             ('需求订单',   '编辑',               'order:order:edit',          2),
             ('需求订单',   '删除',               'order:order:remove',        3),
             ('需求订单',   '提交',               'order:order:submit',        4),
             ('需求订单',   '作废',               'order:order:void',          5),
             ('需求订单',   '审核、反审核、结案', 'order:order:audit',         6),   -- 第 1 级；多级审批加的级看审批规则（架构 §4.8）
             ('需求订单',   '导入',               'order:order:import',        7),
             ('需求订单',   '导出',               'order:order:export',        8),
             ('变更',       '新建',               'order:change:add',          1),
             ('变更',       '编辑',               'order:change:edit',         2),
             ('变更',       '删除',               'order:change:remove',       3),
             ('变更',       '提交',               'order:change:submit',       4),
             ('变更',       '作废',               'order:change:void',         5),
             ('变更',       '审核',               'order:change:audit',        6),
             ('算料',       '算料、重新算料',     'purchase:mrp:add',          1),
             ('算料',       '修改、确认、关闭',   'purchase:mrp:edit',         2),
             ('算料',       '删除',               'purchase:mrp:remove',       3),
             ('采购',       '新建',               'purchase:order:add',        1),
             ('采购',       '编辑',               'purchase:order:edit',       2),
             ('采购',       '删除',               'purchase:order:remove',     3),
             ('采购',       '提交',               'purchase:order:submit',     4),
             ('采购',       '审核、反审核',       'purchase:order:audit',      5),
             ('采购',       '结案、取消结案',     'purchase:order:close',      6),   -- 用户 11-15 定单独一个码，给采购、管理层
             ('采购',       '作废',               'purchase:order:void',       7),
             ('采购',       '导出',               'purchase:order:export',     8),
             ('物料入库单', '新建',               'inventory:doc:add',         1),   -- 入库单、出库单共用（含到货入库、退货、领料、退料）
             ('物料入库单', '编辑',               'inventory:doc:edit',        2),
             ('物料入库单', '删除',               'inventory:doc:remove',      3),
             ('物料入库单', '审核过账、反审核',   'inventory:doc:audit',       4),
             ('物料入库单', '导出',               'inventory:doc:export',      5),
             ('物料盘点单', '新建',               'inventory:count:add',       1),
             ('物料盘点单', '编辑',               'inventory:count:edit',      2),
             ('物料盘点单', '删除',               'inventory:count:remove',    3),   -- 草稿直接删，不作废（用户 11-15 定）
             ('物料盘点单', '提交',               'inventory:count:submit',    4),
             ('物料盘点单', '审核、反审核',       'inventory:count:audit',     5),
             ('物料盘点单', '导出',               'inventory:count:export',    6),
             ('物料盘点单', '导回实盘数',         'inventory:count:import',    7),
             ('库存',       '导出',               'inventory:stock:export',    1),
             ('外发加工单', '排单',               'production:order:add',      1),   -- 外发加工单、成品采购单共用（架构 §4.10）
             ('外发加工单', '编辑',               'production:order:edit',     2),
             ('外发加工单', '删除',               'production:order:remove',   3),
             ('外发加工单', '提交',               'production:order:submit',   4),
             ('外发加工单', '审核、反审核',       'production:order:audit',    5),   -- 第 1 级；多级审批加的级看审批规则
             ('外发加工单', '结案、取消结案',     'production:order:close',    6),   -- 11-15 定，同采购单，给生产跟单、管理层
             ('外发加工单', '作废',               'production:order:void',     7),
             ('外发加工单', '导出',               'production:order:export',   8),
             ('外发加工单', '报进度、改节点计划', 'production:node:edit',      9),
             ('外发加工单', '上报、处理异常',     'production:issue:edit',     10),
             ('外发加工单', '登记回货',           'production:receipt:add',    11),
             ('外发加工单', '作废回货单',         'production:receipt:void',   12),
             ('审批规则',   '修改',               'biz:approval-rule:edit',    1))
     AS b(page_name, name, perms, sort)
  ON b.page_name = pages.name;

-- 2. 常用条款（05 生产单编辑对话框的"填常用条款"）：label 是名字，text_value 是条款文字，点一项把文字接在条款后面。
--    初始的四条是原型的条款模板拆开的，甲方在字典里改
INSERT INTO sys_dict_type (code, name, remark)
VALUES ('prod_terms', '生产单常用条款', 'label 是名字，text_value 是条款文字');

INSERT INTO sys_dict_data (type_code, label, value, sort, text_value)
VALUES ('prod_terms', '次品扣款', 'DEFECT',     1, '次品率超过 3% 的部分按单价扣款。'),
       ('prod_terms', '延误扣款', 'LATE',       2, '交期每延误一天扣货款 0.5%。'),
       ('prod_terms', '送货单',   'DELIVERY',   3, '回货附送货单，写明单号、颜色、尺码、数量。'),
       ('prod_terms', '剩料退回', 'LEFTOVER',   4, '剩料按领料单清点退回。');

-- 3. 授权：照 PRD §3.1 的表一行一个角色（V16 建的八个预置角色）。看 = list、export；改 = 看 + 新建、编辑、删除、提交、作废、导入；
--    审 = 审核、反审核（结案另有码的除外）。已有同编码的角色照样加，迁移不删别的授权
WITH perm_set (name, perms) AS (
    VALUES ('资料包:看',     ARRAY['techpack:pack:list']),
           ('资料包:改',     ARRAY['techpack:pack:list', 'techpack:pack:add', 'techpack:pack:edit', 'techpack:pack:remove',
                                   'techpack:pack:publish']),
           ('看成本',        ARRAY['techpack:cost:query']),
           ('工艺标准:看',   ARRAY['techpack:standard:list']),
           ('工艺标准:改',   ARRAY['techpack:standard:list', 'techpack:standard:edit']),
           ('订单:看',       ARRAY['order:order:list', 'order:order:export']),
           ('订单:改',       ARRAY['order:order:list', 'order:order:export', 'order:order:add', 'order:order:edit',
                                   'order:order:remove', 'order:order:submit', 'order:order:void', 'order:order:import']),
           ('订单:审',       ARRAY['order:order:audit']),
           ('变更:看',       ARRAY['order:change:list']),
           ('变更:改',       ARRAY['order:change:list', 'order:change:add', 'order:change:edit', 'order:change:remove',
                                   'order:change:submit', 'order:change:void']),
           ('变更:审',       ARRAY['order:change:audit']),
           ('算料采购:看',   ARRAY['purchase:mrp:list', 'purchase:order:list', 'purchase:order:export']),
           ('算料采购:改',   ARRAY['purchase:mrp:list', 'purchase:mrp:add', 'purchase:mrp:edit', 'purchase:mrp:remove',
                                   'purchase:order:list', 'purchase:order:export', 'purchase:order:add', 'purchase:order:edit',
                                   'purchase:order:remove', 'purchase:order:submit', 'purchase:order:void']),
           ('采购单:审',     ARRAY['purchase:order:audit']),
           ('采购单:结案',   ARRAY['purchase:order:close']),
           ('出入库库存:看', ARRAY['inventory:doc:list', 'inventory:doc:export', 'inventory:stock:list', 'inventory:stock:export']),
           ('出入库库存:改', ARRAY['inventory:doc:list', 'inventory:doc:export', 'inventory:doc:add', 'inventory:doc:edit',
                                   'inventory:doc:remove', 'inventory:doc:audit', 'inventory:stock:list', 'inventory:stock:export']),
           ('盘点:看',       ARRAY['inventory:count:list', 'inventory:count:export']),
           ('盘点:改',       ARRAY['inventory:count:list', 'inventory:count:export', 'inventory:count:add', 'inventory:count:edit',
                                   'inventory:count:remove', 'inventory:count:submit', 'inventory:count:import']),
           ('盘点:审',       ARRAY['inventory:count:audit']),
           ('生产单:看',     ARRAY['production:order:list', 'production:order:export', 'production:board:list']),
           ('生产单:改',     ARRAY['production:order:list', 'production:order:export', 'production:board:list',
                                   'production:order:add', 'production:order:edit', 'production:order:remove',
                                   'production:order:submit', 'production:order:void', 'production:order:close',
                                   'production:node:edit', 'production:issue:edit', 'production:receipt:add',
                                   'production:receipt:void', 'production:mobile:list']),
           ('生产单:审',     ARRAY['production:order:audit', 'production:order:close'])
), role_grant (code, sets) AS (
    --             资料包、成本、工艺标准                       需求订单、变更                          算料、采购                        出入库、库存、盘点                          生产
    VALUES ('dev',          ARRAY['资料包:改', '工艺标准:看',                  '订单:看', '变更:看',                                                                                           '生产单:看']),
           ('dev_lead',     ARRAY['资料包:改', '工艺标准:改', '看成本',        '订单:看', '变更:看',                                                                                           '生产单:看']),
           ('sales',        ARRAY['资料包:看',                                 '订单:改', '变更:改',                   '算料采购:看',                                                          '生产单:看']),
           ('merchandiser', ARRAY['资料包:看', '工艺标准:看',                  '订单:看', '变更:改',                   '算料采购:看',                    '出入库库存:看',                      '生产单:改']),
           ('buyer',        ARRAY['资料包:看', '看成本',                       '订单:看', '变更:看',                   '算料采购:改', '采购单:结案',     '出入库库存:看', '盘点:看',           '生产单:看']),
           ('warehouse',    ARRAY['资料包:看',                                                                         '算料采购:看',                    '出入库库存:改', '盘点:改',           '生产单:看']),
           ('finance',      ARRAY['资料包:看', '看成本',                       '订单:看', '变更:看',                   '算料采购:看',                    '出入库库存:看', '盘点:看',           '生产单:看']),
           ('manager',      ARRAY['资料包:看', '看成本', '工艺标准:看',        '订单:看', '订单:审', '变更:看', '变更:审', '算料采购:看', '采购单:审', '采购单:结案', '出入库库存:看', '盘点:看', '盘点:审', '生产单:看', '生产单:审'])
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT r.id, m.id
FROM role_grant g
JOIN sys_role r ON r.code = g.code AND r.deleted = false
CROSS JOIN LATERAL unnest(g.sets) AS s(name)
JOIN perm_set p ON p.name = s.name
CROSS JOIN LATERAL unnest(p.perms) AS pp(perm)
JOIN sys_menu m ON m.perms = pp.perm AND m.deleted = false AND m.admin_only = false
ON CONFLICT DO NOTHING;

-- 4. 授了页面、按钮的，再授它的各级上级（目录、"资料包"），同 V16 第 4 步
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false
    JOIN sys_menu m ON m.id = rm.menu_id
    WHERE r.code IN ('dev', 'dev_lead', 'sales', 'merchandiser', 'buyer', 'warehouse', 'finance', 'manager')
      AND m.parent_id <> 0
    UNION
    SELECT up.role_id, m.parent_id
    FROM up
    JOIN sys_menu m ON m.id = up.menu_id
    WHERE m.parent_id <> 0
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, menu_id FROM up
ON CONFLICT DO NOTHING;
