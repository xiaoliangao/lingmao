-- V62：工厂产能（PRD 08.02；甲方 11-27 答 Q60：按品类分开算，理单和跟单每月维护，排满了拦住不让排）。
-- 每家加工厂 × 品类登记一个日产能（件 / 天），从某个月起算；哪个月没再登记，沿用前面最近一次登记的（忘了更新照样查）。
-- 品类可以挂在任何一级：挂在"外套"上管外套和它下面的，挂在"女装"上管整个女装；不填品类 = 这家厂所有款合计。
-- 排单、改草稿的加工厂或交期时查（Service）：交期在这之前、还没回齐的件数 + 这次的，超过 日产能 × 天数 就拦住。
-- 没登记产能的工厂不查
CREATE TABLE prd_factory_capacity (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_id  BIGINT       NOT NULL REFERENCES sup_supplier (id),
    category_id BIGINT       REFERENCES bas_category (id),   -- 空 = 不分品类，这家厂所有款合计
    start_month DATE         NOT NULL,                       -- 从哪个月起（存每月 1 号）
    daily_qty   INT          NOT NULL,                       -- 日产能，件 / 天（按自然日，含周末，填平均每天能出多少）
    remark      VARCHAR(255),
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_prd_capacity_month CHECK (start_month = date_trunc('month', start_month)::date),
    CONSTRAINT ck_prd_capacity_qty CHECK (daily_qty > 0)
);
-- 同一家厂、同一个品类、同一个月只有一条（改就是覆盖这个月的）；不分品类的 category_id 是 NULL，按 0 算
CREATE UNIQUE INDEX uk_prd_factory_capacity ON prd_factory_capacity (factory_id, COALESCE(category_id, 0), start_month);
CREATE INDEX idx_prd_factory_capacity_category ON prd_factory_capacity (category_id);
COMMENT ON TABLE prd_factory_capacity IS '工厂产能（PRD 08.02，甲方 11-27 答 Q60）：加工厂 × 品类的日产能，从 start_month 起，到下一次登记为止';

-- 菜单：生产 › 工厂产能（工厂料账后面、看板前面），按钮"登记产能"。已经有就不建；"生产"目录找不到时不建
WITH dir AS (
    SELECT id FROM sys_menu WHERE parent_id = 0 AND path = '/make' AND deleted = false ORDER BY id LIMIT 1
)
INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
SELECT dir.id, '工厂产能', 2, '/factory-capacity', 'production/capacity/index', 'production:capacity:list', 'Odometer', 27, true, false
FROM dir
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:capacity:list' AND deleted = false);

INSERT INTO sys_menu (parent_id, name, type, perms, sort, visible, admin_only)
SELECT m.id, '登记产能', 3, 'production:capacity:edit', 1, true, false
FROM sys_menu m
WHERE m.perms = 'production:capacity:list' AND m.deleted = false
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'production:capacity:edit' AND deleted = false);

-- 授权：理单、生产跟单维护（Q60 第 2 问），生产经理也能改；管理层只看
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('order_clerk',  'production:capacity:list'), ('order_clerk',  'production:capacity:edit'),
             ('merchandiser', 'production:capacity:list'), ('merchandiser', 'production:capacity:edit'),
             ('prod_manager', 'production:capacity:list'), ('prod_manager', 'production:capacity:edit'),
             ('manager',      'production:capacity:list')) AS g(code, perm)
JOIN sys_role r ON r.code = g.code AND r.deleted = false
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false
ON CONFLICT DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT rm.role_id, m.parent_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.id = rm.menu_id AND m.perms = 'production:capacity:list' AND m.deleted = false AND m.parent_id <> 0
ON CONFLICT DO NOTHING;
