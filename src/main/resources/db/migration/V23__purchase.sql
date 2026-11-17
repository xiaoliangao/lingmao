-- V23：算料单、采购单（docs/api/03、PRD §6.6.1-§6.6.3、§6.6.7，数据库设计 §11、§12）。菜单和按钮权限 V17 已经建好。
-- 已确认、没关闭的算料单是软占用（架构 §5.4）：它的本次使用库存、本次使用在途（mrp_calc_transit）、它生成的采购行（pur_order_item.calc_item_id）
-- 别的算料单算可用量时扣掉。都是查询时现算，不写库存表。
-- 两张表互相指（采购单 → 算料单，本次使用在途 → 采购行），所以按 算料单、算料行、采购单、采购行、本次使用在途 的顺序建。
-- 写法见 V10 开头。

-- 1. 算料单
CREATE TABLE mrp_calc (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no             VARCHAR(32)   NOT NULL,           -- SL + yyyyMMdd + 4 位流水
    company_id     BIGINT        REFERENCES bas_company (id),  -- 算料单不分主体，留空（单头公共列）
    need_date      DATE,                             -- 要料日期：几张订单里最早的"裁剪"计划开始，没排单的按承诺交期倒推；算料时的快照
    prev_calc_id   BIGINT        REFERENCES mrp_calc (id),     -- 重新算料时的上一张；这张确认时上一张自动关闭
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',     -- DRAFT / CONFIRMED / CLOSED；动作改
    close_reason   VARCHAR(16),                      -- ISSUED 订单的生产单都领完料 / MANUAL 手动 / RECALC 重新算料的新单确认了；动作改
    close_by       BIGINT,                           -- 手动关闭的人；自动关闭为空；动作改
    close_time     TIMESTAMPTZ,                      -- 动作改
    input_snapshot JSONB,                            -- 算料时的输入：各订单每个 SKU 的件数、用的生产 BOM、承诺交期（架构 §6）
    audit_by       BIGINT,                           -- 确认人
    audit_time     TIMESTAMPTZ,                      -- 确认时间
    version        INT           NOT NULL DEFAULT 0,
    remark         VARCHAR(500),
    create_by      BIGINT,
    create_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by      BIGINT,
    update_time    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_mrp_calc_no ON mrp_calc (no);
CREATE INDEX idx_mrp_calc_status ON mrp_calc (status);
CREATE INDEX idx_mrp_calc_prev ON mrp_calc (prev_calc_id);
CREATE INDEX idx_mrp_calc_create_by ON mrp_calc (create_by);
CREATE INDEX idx_mrp_calc_company ON mrp_calc (company_id);
-- 一张已确认的算料单同时最多一张重新算料的草稿：两个人同时点"重新算料"，后一个被拦住
CREATE UNIQUE INDEX uk_mrp_calc_next_draft ON mrp_calc (prev_calc_id) WHERE status = 'DRAFT';
COMMENT ON TABLE mrp_calc IS '算料单';

-- 2. 算料单包含的订单
CREATE TABLE mrp_calc_order (
    calc_id  BIGINT NOT NULL REFERENCES mrp_calc (id),
    order_id BIGINT NOT NULL REFERENCES ord_order (id),
    PRIMARY KEY (calc_id, order_id)
);
CREATE INDEX idx_mrp_calc_order_order ON mrp_calc_order (order_id);   -- 订单找它的算料单、齐料状态
COMMENT ON TABLE mrp_calc_order IS '算料单包含的订单';

-- 3. 算料结果行：一个物料 SKU 一行（PRD §6.6.1 的公式）。数字都是算料时存下的；数量除注明外是库存单位
CREATE TABLE mrp_calc_item (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calc_id           BIGINT        NOT NULL REFERENCES mrp_calc (id),
    seq               INT           NOT NULL,
    block             VARCHAR(16)   NOT NULL DEFAULT 'PURCHASE',  -- PURCHASE 我方采购 / FACTORY 工厂自备（只列不算）/ CUSTOMER 待客户提供（TRADE）
    material_sku_id   BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type        VARCHAR(16)   NOT NULL DEFAULT 'OWN',       -- 物权；TRADE 留口子
    owner_id          BIGINT        NOT NULL DEFAULT 0,
    supplier_id       BIGINT        REFERENCES sup_supplier (id), -- BOM 行上的，没有用物料的默认供应商；草稿、已确认没转完时能换
    gross_qty         NUMERIC(14,3) NOT NULL,           -- 毛需求 = Σ 件数 × 单件用量 ×（1 + 损耗率）
    reserve_pct       NUMERIC(6,2)  NOT NULL DEFAULT 0, -- 用的备料比 %
    need_qty          NUMERIC(14,3) NOT NULL,           -- 需求 = 毛需求 ×（1 + 备料比）
    avail_stock_qty   NUMERIC(14,3) NOT NULL DEFAULT 0, -- 可用库存 = 物料仓库存 − 别的算料单占着的
    use_stock_qty     NUMERIC(14,3) NOT NULL DEFAULT 0, -- 本次使用库存，草稿里能改小
    avail_transit_qty NUMERIC(14,3) NOT NULL DEFAULT 0, -- 可用在途：赶得上的、去掉别人占着的
    use_transit_qty   NUMERIC(14,3) NOT NULL DEFAULT 0, -- 本次使用在途 = mrp_calc_transit 合计
    late_transit_qty  NUMERIC(14,3) NOT NULL DEFAULT 0, -- 赶不上的在途，只看
    net_qty           NUMERIC(14,3) NOT NULL,           -- 净需求 = 需求 − 本次使用库存 − 本次使用在途
    purchase_unit     VARCHAR(64),                      -- 算料时物料的采购单位、换算系数
    conversion_factor NUMERIC(14,6),
    suggest_qty       NUMERIC(14,3) NOT NULL DEFAULT 0, -- 建议采购量，采购单位，按最小包装、起订量取整
    ref_price         NUMERIC(14,4),                    -- 参考单价，元 / 采购单位
    est_amount        NUMERIC(14,2),                    -- 预计金额
    converted_qty     NUMERIC(14,3) NOT NULL DEFAULT 0, -- 已转采购，采购单位 = 指着它的没作废的采购行数量合计；动作改
    issued_qty        NUMERIC(14,3) NOT NULL DEFAULT 0, -- 它的订单从物料仓已领（生产领料回写，架构 §5.4）；动作改
    order_nos         VARCHAR(500),                     -- 用到它的订单号、款号，逗号分隔，冗余显示
    style_codes       VARCHAR(500),
    CHECK (use_stock_qty >= 0 AND use_transit_qty >= 0 AND suggest_qty >= 0 AND converted_qty >= 0)
);
CREATE INDEX idx_mrp_item_calc ON mrp_calc_item (calc_id, seq);
CREATE UNIQUE INDEX uk_mrp_item_sku ON mrp_calc_item (calc_id, block, material_sku_id, owner_type, owner_id);
CREATE INDEX idx_mrp_item_sku ON mrp_calc_item (material_sku_id);
CREATE INDEX idx_mrp_item_supplier ON mrp_calc_item (supplier_id);
COMMENT ON TABLE mrp_calc_item IS '算料结果行';

-- 4. 采购单。结算方式、税率草稿里可以空着（供应商上没设），提交时必填
CREATE TABLE pur_order (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no               VARCHAR(32)   NOT NULL,           -- CG + yyyyMMdd + 4 位流水
    company_id       BIGINT        NOT NULL REFERENCES bas_company (id),
    supplier_id      BIGINT        NOT NULL REFERENCES sup_supplier (id),  -- 只能选合作中的
    purchase_type    VARCHAR(16)   NOT NULL,           -- ORDER 订单采购 / STOCK 备货采购 / SAMPLE 打样采购
    calc_id          BIGINT        REFERENCES mrp_calc (id),  -- 从哪张算料单生成；行上另有 calc_item_id
    buyer_id         BIGINT        NOT NULL REFERENCES sys_user (id),  -- 采购员；数据范围"本人"按制单人、采购员
    order_date       DATE          NOT NULL,
    delivery_date    DATE          NOT NULL,           -- 交货日期，默认 = 下单日 + 各行物料采购周期最长的（没设按 7 天）
    warehouse_id     BIGINT        NOT NULL REFERENCES bas_warehouse (id),  -- 交货地点：物料仓或工厂仓（直发工厂）
    settle_method    VARCHAR(64),                      -- 字典 settle_method，默认带供应商
    tax_pct          NUMERIC(6,2),                     -- 税率 %，默认带供应商
    tax_included     BOOLEAN       NOT NULL DEFAULT true,
    freight_included BOOLEAN       NOT NULL DEFAULT true,
    total_qty        NUMERIC(14,3) NOT NULL DEFAULT 0, -- 各行采购单位数量直接相加，只显示
    total_amount     NUMERIC(14,2) NOT NULL DEFAULT 0, -- 多级审批按它加级
    status           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PENDING / APPROVED / RUNNING 部分到货 / DONE 已到齐 / CLOSED / VOIDED；动作改
    audit_by         BIGINT,
    audit_time       TIMESTAMPTZ,
    version          INT           NOT NULL DEFAULT 0,
    remark           VARCHAR(500),
    create_by        BIGINT,
    create_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by        BIGINT,
    update_time      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_pur_order_no ON pur_order (no);
CREATE INDEX idx_pur_order_company ON pur_order (company_id);
CREATE INDEX idx_pur_order_supplier ON pur_order (supplier_id);
CREATE INDEX idx_pur_order_buyer ON pur_order (buyer_id);
CREATE INDEX idx_pur_order_create_by ON pur_order (create_by);
CREATE INDEX idx_pur_order_warehouse ON pur_order (warehouse_id);
CREATE INDEX idx_pur_order_calc ON pur_order (calc_id);
CREATE INDEX idx_pur_order_status ON pur_order (status);
COMMENT ON TABLE pur_order IS '采购单';

-- 5. 计划用于款号：备货采购选填，只是备注，不预留（PRD §6.6.7）
CREATE TABLE pur_order_style (
    purchase_order_id BIGINT NOT NULL REFERENCES pur_order (id),
    style_id          BIGINT NOT NULL REFERENCES sty_style (id),
    PRIMARY KEY (purchase_order_id, style_id)
);
CREATE INDEX idx_pur_order_style_style ON pur_order_style (style_id);
COMMENT ON TABLE pur_order_style IS '采购单计划用于的款号';

-- 6. 采购明细。数量、单价、已入库、已退货都按采购单位；未入库 = 数量 −（已入库 − 已退货），现算不存
CREATE TABLE pur_order_item (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    purchase_order_id   BIGINT        NOT NULL REFERENCES pur_order (id),
    seq                 INT           NOT NULL,
    material_sku_id     BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type          VARCHAR(16)   NOT NULL DEFAULT 'OWN',
    owner_id            BIGINT        NOT NULL DEFAULT 0,
    purchase_unit       VARCHAR(64)   NOT NULL,         -- 下单时物料的采购单位、换算系数（1 采购单位 = n 库存单位）
    conversion_factor   NUMERIC(14,6) NOT NULL,
    qty                 NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    price               NUMERIC(14,4) NOT NULL,         -- 元 / 采购单位
    amount              NUMERIC(14,2) NOT NULL,
    price_source_id     BIGINT        REFERENCES prc_price (id),  -- 默认单价取自哪条价格档案
    price_change_reason VARCHAR(255),                   -- 单价和档案现价不一样时必填
    line_due_date       DATE,                           -- 行交期，空 = 单头交货日期
    calc_item_id        BIGINT        REFERENCES mrp_calc_item (id),  -- 来源算料行；重新算料的新单确认时改指新单的行
    received_qty        NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (received_qty >= 0),  -- 采购入库单过账回写；动作改
    returned_qty        NUMERIC(14,3) NOT NULL DEFAULT 0 CHECK (returned_qty >= 0),  -- 采购退货出库单过账回写；动作改
    expect_date         DATE,                           -- 预计到货，审核后在"待到料"里随时改；动作改
    follow_note         VARCHAR(255)                    -- 跟进备注；动作改
);
CREATE INDEX idx_pur_item_order ON pur_order_item (purchase_order_id, seq);
CREATE INDEX idx_pur_item_sku ON pur_order_item (material_sku_id);
CREATE INDEX idx_pur_item_calc ON pur_order_item (calc_item_id);
CREATE INDEX idx_pur_item_price ON pur_order_item (price_source_id);
-- 在途、待到料：没入齐的行
CREATE INDEX idx_pur_item_open ON pur_order_item (material_sku_id) WHERE received_qty - returned_qty < qty;
COMMENT ON TABLE pur_order_item IS '采购明细';

-- 7. 本次使用在途：算料行用了哪条采购行的在途、用多少（库存单位）
CREATE TABLE mrp_calc_transit (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    calc_item_id     BIGINT        NOT NULL REFERENCES mrp_calc_item (id),
    purchase_item_id BIGINT        NOT NULL REFERENCES pur_order_item (id),
    qty              NUMERIC(14,3) NOT NULL CHECK (qty > 0),
    eta              DATE                               -- 算料时那行的预计到货（没填用交期），快照
);
CREATE UNIQUE INDEX uk_mrp_transit ON mrp_calc_transit (calc_item_id, purchase_item_id);
CREATE INDEX idx_mrp_transit_purchase ON mrp_calc_transit (purchase_item_id);
COMMENT ON TABLE mrp_calc_transit IS '算料行用了哪几条采购行的在途';
