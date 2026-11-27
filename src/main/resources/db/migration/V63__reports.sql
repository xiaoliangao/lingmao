-- V63：报表和预警中心（甲方 11-27 答 Q66、Q67、Q53，RFC 019 §3）。不加表：都是现算的（架构 §14.2，只读 SQL）。
-- 新目录"报表"（成品后面、资料前面），五个页面：
--   预警中心：首页那些提醒（生产延期、到料逾期、证照到期……）放到一页上，再加呆滞料；谁看到什么还按各自的权限
--   供应商绩效：物料供应商的采购到货及时率；加工厂的交期达成率、次品率（Q66 每周看的两张）
--   在做件数：各品类线在做的件数（Q66 每月看）
--   订单毛利：销售额 − 按资料包核价估的成本（Q66 每周看；接口另要看订单价格、看成本的权限）
--   呆滞料：多少天没动算呆滞能改（Q67 答"待定"），默认 90 天
-- 呆滞天数存 sys_config 的 report.stale_days（没有这一行按 90），这里不插

WITH dir AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    SELECT 0, '报表', 1, '/report', 'DataAnalysis', 60
    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = '/report' AND deleted = false)
    RETURNING id
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
    SELECT dir.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort, true, false
    FROM dir
    CROSS JOIN (VALUES ('预警中心',   '/alerts',               'report/alerts/index',   'report:alert:list',    'Bell',      10),
                       ('供应商绩效', '/supplier-performance', 'report/supplier/index', 'report:supplier:list', 'Medal',     20),
                       ('在做件数',   '/line-wip',             'report/wip/index',      'report:wip:list',      'Histogram', 30),
                       ('订单毛利',   '/order-margin',         'report/margin/index',   'report:margin:list',   'Money',     40),
                       ('呆滞料',     '/stale-materials',      'report/stale/index',    'report:stale:list',    'Box',       50))
         AS p(name, path, component, perms, icon, sort)
    RETURNING id, name
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT pages.id, b.name, 3, b.perms, b.sort, false
FROM pages
JOIN (VALUES ('呆滞料', '导出',       'report:stale:export', 1),
             ('呆滞料', '改呆滞天数', 'report:stale:edit',   2)) AS b(page, name, perms, sort)
  ON b.page = pages.name;

-- 授权：预警中心给各业务岗位（"物料单审核"只是叠在别的角色上的小模板，不给）；其余按看的人给
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('report:alert:list',    ARRAY['dev', 'dev_lead', 'sales', 'sales_lead', 'merchandiser', 'buyer', 'warehouse', 'finance',
                                            'manager', 'prod_manager', 'order_clerk']),
             ('report:supplier:list', ARRAY['manager', 'prod_manager', 'buyer', 'merchandiser', 'order_clerk', 'finance']),
             ('report:wip:list',      ARRAY['manager', 'prod_manager', 'merchandiser', 'order_clerk', 'sales_lead']),
             ('report:margin:list',   ARRAY['manager', 'finance']),
             ('report:stale:list',    ARRAY['manager', 'prod_manager', 'warehouse', 'buyer', 'order_clerk', 'finance']),
             ('report:stale:export',  ARRAY['manager', 'prod_manager', 'warehouse', 'buyer', 'order_clerk', 'finance']),
             ('report:stale:edit',    ARRAY['manager', 'prod_manager'])) AS g(perm, roles)
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false
JOIN sys_role r ON r.code = ANY(g.roles) AND r.deleted = false
ON CONFLICT DO NOTHING;

-- 上级（页面、目录）
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_menu m ON m.id = rm.menu_id AND m.perms LIKE 'report:%' AND m.deleted = false
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
