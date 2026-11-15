-- V16：第 2 批的菜单、按钮权限码，八个预置角色模板和它们的授权（RFC 003 §13，权限码另见 RFC 004 §8、RFC 005 §7、RFC 006 §8）。
-- 菜单和授权放在第 2 批最后一个文件：对着 RFC 003 §13.2 的表（PRD §3.1）一张就能审完。以后各批的菜单迁移各自建目录、补授权。

-- 1. 业务菜单（首页这一侧，admin_only = false）的两个顶层目录，和它们下面的页面、按钮。
--    顶层目录照原型侧边栏的分组，这一批只建用得到的两个；sort 按原型的顺序留好空：
--    开发 10、订单 20、物料 30、生产 40、成品 50、对账结算 60、资料 70、系统 80。
--    目录的 path 不进地址（页面的 path 以 / 开头，是绝对地址），只用来让以后的迁移找到它：
--    parent_id = 0 AND path = '/master' AND deleted = false。
--    页面的 component 对应 web/src/views 下的文件；文件还没做时前端显示"还没上线"，所以菜单可以先建。
--    "价格档案"不是页面（价格在物料、供应商、款式三个详情页的页签里看），visible = false、没有组件，
--    只是让它的三个按钮在角色页里有地方勾（RFC 006 §8）。
--    菜单本身带"看"的权限码（xxx:list），按钮带其余的，和 V4 的系统管理一样
WITH dirs AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    VALUES (0, '开发', 1, '/dev', 'Brush', 10),
           (0, '资料', 1, '/master', 'Files', 70)
    RETURNING id, path
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible)
    SELECT dirs.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort, p.visible
    FROM dirs
    JOIN (VALUES ('/dev',    '款式',     '/styles',    'style/style/index',       'style:style:list',       'Goods',    30, true),
                 ('/master', '供应商',   '/suppliers', 'supplier/supplier/index', 'supplier:supplier:list', 'Shop',     10, true),
                 ('/master', '物料',     '/materials', 'basic/material/index',    'basic:material:list',    'Box',      40, true),
                 ('/master', '基础',     '/basics',    'basic/basics/index',      'basic:basics:list',      'Grid',     50, true),
                 ('/master', '价格档案', NULL,         NULL,                      NULL,                     'PriceTag', 90, false))
         AS p(dir_path, name, path, component, perms, icon, sort, visible)
      ON p.dir_path = dirs.path
    RETURNING id, name
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort)
SELECT pages.id, b.name, 3, b.perms, b.sort
FROM pages
JOIN (VALUES ('款式',     '新增',           'style:style:add',          1),
             ('款式',     '修改',           'style:style:edit',         2),
             ('款式',     '删除',           'style:style:remove',       3),
             ('款式',     '导入',           'style:style:import',       4),
             ('款式',     '导出',           'style:style:export',       5),
             ('供应商',   '新增',           'supplier:supplier:add',    1),
             ('供应商',   '修改',           'supplier:supplier:edit',   2),
             ('供应商',   '删除',           'supplier:supplier:remove', 3),
             ('供应商',   '导入',           'supplier:supplier:import', 4),
             ('供应商',   '导出',           'supplier:supplier:export', 5),
             ('供应商',   '准入审批、拉黑', 'supplier:supplier:admit',  6),   -- 通过、驳回准入，批改动，拉黑、解除；不能批自己提的
             ('供应商',   '看银行全号',     'supplier:supplier:bank',   7),   -- 没有它的人只看到前 4 后 4 位（RFC 004 §5）
             ('物料',     '新增',           'basic:material:add',       1),
             ('物料',     '修改',           'basic:material:edit',      2),
             ('物料',     '删除',           'basic:material:remove',    3),
             ('物料',     '导入',           'basic:material:import',    4),
             ('物料',     '导出',           'basic:material:export',    5),
             -- 颜色、尺码组、品类、物料分类、仓库、主体公司六种资料共用一组码（RFC 003 §15）
             ('基础',     '新增',           'basic:basics:add',         1),
             ('基础',     '修改',           'basic:basics:edit',        2),
             ('基础',     '删除',           'basic:basics:remove',      3),
             -- 物料价采购能改、加工价生产跟单能改，所以分两个码（PRD §3.1）；录错了自己能作废，录和作废不分开
             ('价格档案', '看价格记录',     'price:price:list',         1),
             ('价格档案', '录、作废物料价', 'price:material:edit',      2),
             ('价格档案', '录、作废加工价', 'price:process:edit',       3))
     AS b(page_name, name, perms, sort)
  ON b.page_name = pages.name;

-- 2. 八个预置角色模板（RFC 003 §13.2），数据范围照 PRD §3.2：业务、生产跟单、采购只看自己建的单据，其余看全部。
--    已经有同编码的角色（甲方在试用环境里手建过）就不动它，下面的授权照样加到它上面（只是加了这一批的新菜单）；
--    同名不同编码的会多出一份，迁移不删
INSERT INTO sys_role (code, name, sort, data_scope, remark)
VALUES ('dev',          '开发',     1, 'ALL',  '预置模板，权限和数据范围可以改'),
       ('dev_lead',     '开发主管', 2, 'ALL',  '预置模板，权限和数据范围可以改'),
       ('sales',        '业务',     3, 'SELF', '预置模板，权限和数据范围可以改'),
       ('merchandiser', '生产跟单', 4, 'SELF', '预置模板，权限和数据范围可以改'),
       ('buyer',        '采购',     5, 'SELF', '预置模板，权限和数据范围可以改'),
       ('warehouse',    '仓管',     6, 'ALL',  '预置模板，权限和数据范围可以改'),
       ('finance',      '财务',     7, 'ALL',  '预置模板，权限和数据范围可以改'),
       ('manager',      '管理层',   8, 'ALL',  '预置模板，权限和数据范围可以改')
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

-- 3. 授权：照 RFC 003 §13.2 的表一行一个角色。看 = list、export；改 = 看 + add、edit、remove、import（基础没有导入导出）
WITH perm_set (name, perms) AS (
    VALUES ('基础:看',     ARRAY['basic:basics:list']),
           ('基础:改',     ARRAY['basic:basics:list', 'basic:basics:add', 'basic:basics:edit', 'basic:basics:remove']),
           ('物料:看',     ARRAY['basic:material:list', 'basic:material:export']),
           ('物料:改',     ARRAY['basic:material:list', 'basic:material:export', 'basic:material:add',
                                 'basic:material:edit', 'basic:material:remove', 'basic:material:import']),
           ('供应商:看',   ARRAY['supplier:supplier:list', 'supplier:supplier:export']),
           ('供应商:改',   ARRAY['supplier:supplier:list', 'supplier:supplier:export', 'supplier:supplier:add',
                                 'supplier:supplier:edit', 'supplier:supplier:remove', 'supplier:supplier:import']),
           ('供应商:准入', ARRAY['supplier:supplier:admit']),
           ('银行全号',    ARRAY['supplier:supplier:bank']),
           ('款式:看',     ARRAY['style:style:list', 'style:style:export']),
           ('款式:改',     ARRAY['style:style:list', 'style:style:export', 'style:style:add',
                                 'style:style:edit', 'style:style:remove', 'style:style:import']),
           ('价格档案:看', ARRAY['price:price:list']),
           ('物料价:改',   ARRAY['price:material:edit']),
           ('加工价:改',   ARRAY['price:process:edit'])
), role_grant (code, sets) AS (
    VALUES ('dev',          ARRAY['基础:改', '物料:改', '供应商:看', '款式:改', '价格档案:看']),
           ('dev_lead',     ARRAY['基础:改', '物料:改', '供应商:看', '款式:改', '价格档案:看']),
           ('sales',        ARRAY['基础:看', '物料:看', '供应商:看', '款式:看', '价格档案:看']),
           ('merchandiser', ARRAY['基础:看', '物料:看', '供应商:看', '款式:看', '价格档案:看', '加工价:改']),
           ('buyer',        ARRAY['基础:看', '物料:改', '供应商:改', '银行全号', '款式:看', '价格档案:看', '物料价:改']),
           ('warehouse',    ARRAY['基础:看', '物料:看', '供应商:看', '款式:看']),
           ('finance',      ARRAY['基础:看', '物料:看', '供应商:看', '银行全号', '款式:看', '价格档案:看']),
           ('manager',      ARRAY['基础:看', '物料:看', '供应商:看', '供应商:准入', '银行全号', '款式:看',
                                  '价格档案:看', '物料价:改', '加工价:改'])
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

-- 4. 授了页面、按钮的，再授它的各级上级（目录、"价格档案"）：侧边栏只列授了的目录和页面，目录没授，下面的页面就挂不上去。
--    和角色页保存时把"半选"的上级一起存是一回事
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
