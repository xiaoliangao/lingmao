-- V46：成品（二期，RFC 008、docs/api/07、数据库设计 §18）：成品入库单、成品出库单、成品库存、库存日志，
-- 两个原因字典，"待入库"的启用日期，菜单、按钮和角色模板的授权。
-- 规则照物料（V21）：审核即过账，库存只能由单据改，不能为负；过过账的单不能删、只能作废。

-- 1. 成品入库单、出库单：一张表两种单据（架构 §4.10），按 kind 分
CREATE TABLE fg_doc (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                VARCHAR(32)   NOT NULL,           -- CR 成品入库单 / CC 成品出库单 + yyyyMMdd + 4 位流水
    company_id        BIGINT        NOT NULL REFERENCES bas_company (id),  -- 回货入库跟回货单，发货跟订单
    kind              VARCHAR(8)    NOT NULL,           -- IN / OUT；建单时定
    doc_type          VARCHAR(16)   NOT NULL,           -- IN：RECEIPT_IN 回货入库 / OTHER_IN 其他入库；OUT：SHIP_OUT 发货出库 / OTHER_OUT 其他出库
    status            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / APPROVED（审核即过账）/ VOIDED（过过账又反审核回来的才能作废）；动作改
    from_warehouse_id BIGINT        REFERENCES bas_warehouse (id),   -- 出库单的出仓（成品仓）
    to_warehouse_id   BIGINT        REFERENCES bas_warehouse (id),   -- 入库单的入仓（成品仓）
    prd_receipt_id    BIGINT        REFERENCES prd_receipt (id),     -- 回货入库：回货单；一张回货单可以分几次入
    order_id          BIGINT        REFERENCES ord_order (id),       -- 发货出库：需求订单
    doc_date          DATE          NOT NULL,           -- 实际日期，不能晚于今天
    reason            VARCHAR(64),                      -- 其他入库、其他出库必填：字典 fg_in_reason / fg_out_reason
    ship_to           VARCHAR(255),                     -- 发货出库：发到哪，默认订单的送货要求
    logistics_no      VARCHAR(64),
    audit_by          BIGINT,
    audit_time        TIMESTAMPTZ,
    version           INT           NOT NULL DEFAULT 0, -- 乐观锁：保存草稿、每个动作都 +1
    remark            VARCHAR(500),
    create_by         BIGINT,
    create_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (kind IN ('IN', 'OUT')),
    CHECK ((kind = 'IN' AND to_warehouse_id IS NOT NULL AND from_warehouse_id IS NULL)
        OR (kind = 'OUT' AND from_warehouse_id IS NOT NULL AND to_warehouse_id IS NULL)),
    CHECK ((doc_type = 'RECEIPT_IN') = (prd_receipt_id IS NOT NULL)),
    CHECK ((doc_type = 'SHIP_OUT') = (order_id IS NOT NULL))
);
CREATE UNIQUE INDEX uk_fg_doc_no ON fg_doc (no);
CREATE INDEX idx_fg_doc_kind ON fg_doc (kind, status);
-- 回货单的累计已入由服务层在回货单锁内查（RFC 008 §3.3）
CREATE INDEX idx_fg_doc_receipt ON fg_doc (prd_receipt_id);
CREATE INDEX idx_fg_doc_order ON fg_doc (order_id);
CREATE INDEX idx_fg_doc_from ON fg_doc (from_warehouse_id);
CREATE INDEX idx_fg_doc_to ON fg_doc (to_warehouse_id);
CREATE INDEX idx_fg_doc_company ON fg_doc (company_id);
CREATE INDEX idx_fg_doc_date ON fg_doc (doc_date);
CREATE INDEX idx_fg_doc_create_by ON fg_doc (create_by);
COMMENT ON TABLE fg_doc IS '成品出入库单';

CREATE TABLE fg_doc_item (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id              BIGINT        NOT NULL REFERENCES fg_doc (id),
    seq                 INT           NOT NULL,
    style_sku_id        BIGINT        NOT NULL REFERENCES sty_style_sku (id),
    qty                 INT           NOT NULL CHECK (qty > 0),        -- 件；方向由 kind 定
    prd_receipt_item_id BIGINT        REFERENCES prd_receipt_item (id), -- 回货入库：对应的回货明细
    order_item_id       BIGINT        REFERENCES ord_order_item (id),   -- 发货出库：对应的订单行
    price               NUMERIC(14,4),                                  -- 发货出库：订单行单价；别的为空
    amount              NUMERIC(14,2),
    remark              VARCHAR(255)
);
-- 一张单一个 SKU 一行
CREATE UNIQUE INDEX uk_fg_doc_item_sku ON fg_doc_item (doc_id, style_sku_id);
CREATE INDEX idx_fg_doc_item ON fg_doc_item (doc_id, seq);
CREATE INDEX idx_fg_doc_item_sku ON fg_doc_item (style_sku_id);
CREATE INDEX idx_fg_doc_item_receipt ON fg_doc_item (prd_receipt_item_id);
CREATE INDEX idx_fg_doc_item_order ON fg_doc_item (order_item_id);
COMMENT ON TABLE fg_doc_item IS '成品出入库明细';

-- 2. 成品库存：仓库 × 款式 SKU × 货位（0 = 不分，二期货位用；进唯一键，理由同 inv_stock，架构 §5.1）
CREATE TABLE fg_stock (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id BIGINT        NOT NULL REFERENCES bas_warehouse (id),
    style_sku_id BIGINT        NOT NULL REFERENCES sty_style_sku (id),
    location_id  BIGINT        NOT NULL DEFAULT 0,
    qty          INT           NOT NULL DEFAULT 0 CHECK (qty >= 0),   -- 结存，件；条件更新保证不为负，这里再兜一层
    update_time  TIMESTAMPTZ   NOT NULL DEFAULT now()                 -- 最近变动时间
);
CREATE UNIQUE INDEX uk_fg_stock ON fg_stock (warehouse_id, style_sku_id, location_id);
CREATE INDEX idx_fg_stock_sku ON fg_stock (style_sku_id);
COMMENT ON TABLE fg_stock IS '成品库存';

-- 3. 库存日志：只插不改；反审核再记一条反方向的。不指向明细（一张单一个 SKU 一行，单据 + SKU 对得上），
--    反审核回草稿以后删行、改行不会被外键拦住
CREATE TABLE fg_stock_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fg_doc_id    BIGINT        NOT NULL REFERENCES fg_doc (id),
    change_type  VARCHAR(16)   NOT NULL,           -- 单据的 doc_type
    doc_no       VARCHAR(32)   NOT NULL,
    biz_date     DATE          NOT NULL,           -- 单据的实际日期
    warehouse_id BIGINT        NOT NULL REFERENCES bas_warehouse (id),
    style_sku_id BIGINT        NOT NULL REFERENCES sty_style_sku (id),
    location_id  BIGINT        NOT NULL DEFAULT 0,
    change_qty   INT           NOT NULL,           -- 入正出负
    balance_qty  INT           NOT NULL,           -- 变动后结存
    create_by    BIGINT,
    create_time  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_fg_log_stock ON fg_stock_log (warehouse_id, style_sku_id, id);
CREATE INDEX idx_fg_log_doc ON fg_stock_log (fg_doc_id);
CREATE INDEX idx_fg_log_sku ON fg_stock_log (style_sku_id);
CREATE INDEX idx_fg_log_date ON fg_stock_log (biz_date);
COMMENT ON TABLE fg_stock_log IS '成品库存日志';

-- 4. 原因字典（原型 FG_OUT_REASONS），甲方在字典里改
INSERT INTO sys_dict_type (code, name, remark)
VALUES ('fg_in_reason',  '成品其他入库原因', NULL),
       ('fg_out_reason', '成品其他出库原因', NULL)
ON CONFLICT (code) WHERE deleted = false DO NOTHING;

INSERT INTO sys_dict_data (type_code, label, value, sort)
VALUES ('fg_in_reason',  '期初',     'OPENING', 1),
       ('fg_in_reason',  '返修合格', 'REWORKED', 2),
       ('fg_in_reason',  '退回',     'RETURNED', 3),
       ('fg_in_reason',  '其他',     'OTHER',    4),
       ('fg_out_reason', '样品',     'SAMPLE',   1),
       ('fg_out_reason', '报废',     'SCRAP',    2),
       ('fg_out_reason', '其他',     'OTHER',    3)
ON CONFLICT (type_code, value) WHERE deleted = false DO NOTHING;

-- 5. "待入库"只列这一天以后登记的回货（RFC 008 §3.3）：装这一版的日期。之前的回货当作已经在成品仓里，
--    上线时用"其他入库 · 期初"录；已经有这个设置的（重装）不动
INSERT INTO sys_config (config_key, config_value, remark)
VALUES ('fg.receipt_from', to_jsonb(to_char((now() AT TIME ZONE 'Asia/Shanghai')::date, 'YYYY-MM-DD')),
        '成品"待入库"只列这天以后登记的回货')
ON CONFLICT (config_key) DO NOTHING;

-- 6. 菜单：顶层目录"成品"（sort 50，V16 留的空）和三个页面；按钮照 V17，两种单共用的挂在"成品入库单"下面
WITH dir AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    VALUES (0, '成品', 1, '/fg', 'Box', 50)
    RETURNING id
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
    SELECT dir.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort, true, false
    FROM dir
    CROSS JOIN (VALUES ('成品入库单', '/fg-in',    'fg/stock-in/index',  'fg:doc:list',   'Download', 10),
                       ('成品出库单', '/fg-out',   'fg/stock-out/index', 'fg:doc:list',   'Upload',   20),
                       ('成品库存',   '/fg-stock', 'fg/stock/index',     'fg:stock:list', 'House',    30))
         AS p(name, path, component, perms, icon, sort)
    RETURNING id, name
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT pages.id, b.name, 3, b.perms, b.sort, false
FROM pages
JOIN (VALUES ('成品入库单', '新建',             'fg:doc:add',      1),   -- 入库单、出库单共用（含由回货单生成）
             ('成品入库单', '编辑',             'fg:doc:edit',     2),
             ('成品入库单', '删除、作废',       'fg:doc:remove',   3),
             ('成品入库单', '审核过账、反审核', 'fg:doc:audit',    4),
             ('成品入库单', '导出',             'fg:doc:export',   5),
             ('成品库存',   '导出',             'fg:stock:export', 1))
     AS b(page_name, name, perms, sort)
  ON b.page_name = pages.name;

-- 7. 角色模板（RFC 008 §5，照"物料入库单、出库单、库存"那一行）：仓管给全部；业务、生产跟单、生产经理、财务、管理层看；
--    开发、采购不给。已有同编码的角色照样加，迁移不删别的授权
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT r.id, m.id
FROM (VALUES ('warehouse',    ARRAY['fg:doc:list', 'fg:doc:add', 'fg:doc:edit', 'fg:doc:remove', 'fg:doc:audit', 'fg:doc:export',
                                    'fg:stock:list', 'fg:stock:export']),
             ('sales',        ARRAY['fg:doc:list', 'fg:stock:list']),
             ('merchandiser', ARRAY['fg:doc:list', 'fg:stock:list']),
             ('prod_manager', ARRAY['fg:doc:list', 'fg:stock:list']),
             ('finance',      ARRAY['fg:doc:list', 'fg:stock:list']),
             ('manager',      ARRAY['fg:doc:list', 'fg:stock:list']))
     AS g(code, perms)
JOIN sys_role r ON r.code = g.code AND r.deleted = false
CROSS JOIN LATERAL unnest(g.perms) AS pp(perm)
JOIN sys_menu m ON m.perms = pp.perm AND m.deleted = false AND m.admin_only = false
ON CONFLICT DO NOTHING;

-- 授了页面、按钮的，再授它的各级上级（目录"成品"），同 V16 第 4 步
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_role r ON r.id = rm.role_id AND r.deleted = false
    JOIN sys_menu m ON m.id = rm.menu_id
    WHERE r.code IN ('warehouse', 'sales', 'merchandiser', 'prod_manager', 'finance', 'manager')
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
