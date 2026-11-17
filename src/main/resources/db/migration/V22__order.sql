-- V22：需求订单、订单变更单（docs/api/02、PRD §6.5，数据库设计 §10）。菜单和按钮权限 V17 已经建好。
-- 已审核的订单不能直接改，走变更单：变更单审核时按 ord_change_item 一处一处写回订单（写回前核对"原"还等于订单现在的值）。
-- 变更单把一个 SKU 改成 0 件时订单行留着、件数记 0：变更明细指着它，删了就对不上；再加回来改的还是这一行。
-- 写法见 V10 开头。

-- 1. 需求订单
CREATE TABLE ord_order (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                VARCHAR(32)   NOT NULL,           -- DD + yyyyMMdd + 4 位流水
    company_id        BIGINT        NOT NULL REFERENCES bas_company (id),
    order_type        VARCHAR(64)   NOT NULL,           -- 字典 order_type；TRADE 关时不收 CUSTOMER
    customer_id       BIGINT        REFERENCES bas_customer (id),  -- 客户：TRADE 留口子，关着时为空
    customer_order_no VARCHAR(64),                      -- 客户订单号：同上
    contract_no       VARCHAR(64),                      -- 合同号，能按它搜
    brand             VARCHAR(64),                      -- 字典 brand
    order_date        DATE          NOT NULL,           -- 下单日期
    required_date     DATE          NOT NULL,           -- 要求交期；变更单能改
    commit_date       DATE,                             -- 承诺交期：审核时每一级都填，中间级填的先写这里当下一级的默认值；动作改
    sales_id          BIGINT        NOT NULL REFERENCES sys_user (id),  -- 业务员
    merch_id          BIGINT        NOT NULL REFERENCES sys_user (id),  -- 跟单员；数据范围"本人"按制单人、业务员、跟单员
    currency          VARCHAR(64)   NOT NULL DEFAULT 'CNY',  -- 字典 currency，金额不换算
    delivery_req      VARCHAR(500),                     -- 送货要求
    total_qty         INT           NOT NULL DEFAULT 0, -- 总件数，保存、变更写回时由明细算好
    total_amount      NUMERIC(14,2) NOT NULL DEFAULT 0, -- 总金额，同上；多级审批按它加级
    source            VARCHAR(16)   NOT NULL DEFAULT 'MANUAL',  -- MANUAL 手工 / IMPORT Excel 导入 / REPEAT 翻单（二期）
    status            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',   -- DRAFT / PENDING / APPROVED / RUNNING 生产中 / DONE / CLOSED / VOIDED 已取消；动作改
    audit_by          BIGINT,                           -- 最后一级审核人，各级见 biz_approval_step
    audit_time        TIMESTAMPTZ,
    version           INT           NOT NULL DEFAULT 0, -- 乐观锁：保存草稿、每个动作（多级审批的每一级）、变更写回都 +1
    remark            VARCHAR(500),
    create_by         BIGINT,
    create_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_ord_order_no ON ord_order (no);
CREATE INDEX idx_ord_order_company ON ord_order (company_id);
CREATE INDEX idx_ord_order_status ON ord_order (status);
CREATE INDEX idx_ord_order_merch ON ord_order (merch_id);
CREATE INDEX idx_ord_order_sales ON ord_order (sales_id);
CREATE INDEX idx_ord_order_create_by ON ord_order (create_by);   -- 数据范围"本人"三列 OR，各有一个索引
CREATE INDEX idx_ord_order_customer ON ord_order (customer_id);
CREATE INDEX idx_ord_order_contract ON ord_order (contract_no);
COMMENT ON TABLE ord_order IS '需求订单';

-- 2. 需求订单明细：一行一个款式 SKU，页面按款画颜色 × 尺码矩阵。件数 0 只会是变更单减掉的（见开头）
CREATE TABLE ord_order_item (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT        NOT NULL REFERENCES ord_order (id),
    seq           INT           NOT NULL,
    style_id      BIGINT        NOT NULL REFERENCES sty_style (id),      -- 冗余一份，按款汇总
    style_sku_id  BIGINT        NOT NULL REFERENCES sty_style_sku (id),
    qty           INT           NOT NULL CHECK (qty >= 0),               -- 保存草稿要 > 0
    price         NUMERIC(14,4),                                         -- 单价，按尺码另填的也落在这里
    amount        NUMERIC(14,2),                                         -- 件数 × 单价；没单价为空
    line_due_date DATE,                                                  -- 行交期，空 = 用单头的要求交期
    tech_pack_id  BIGINT        REFERENCES tp_tech_pack (id),            -- 审核时锁定的生产 BOM，反审核清掉；动作改
    scheduled_qty INT           NOT NULL DEFAULT 0 CHECK (scheduled_qty >= 0),  -- 已排产，排单回写（生产单草稿也算）；动作改
    returned_qty  INT           NOT NULL DEFAULT 0 CHECK (returned_qty >= 0),   -- 已回货，回货回写；动作改
    remark        VARCHAR(255)
);
CREATE INDEX idx_ord_item_order ON ord_order_item (order_id, seq);
CREATE UNIQUE INDEX uk_ord_item_sku ON ord_order_item (order_id, style_sku_id);
CREATE INDEX idx_ord_item_style ON ord_order_item (style_id);
CREATE INDEX idx_ord_item_sku ON ord_order_item (style_sku_id);
CREATE INDEX idx_ord_item_pack ON ord_order_item (tech_pack_id);
COMMENT ON TABLE ord_order_item IS '需求订单明细';

-- 3. 订单变更单。驳回回到草稿，reject_reason 记原因（列表要按"已驳回"筛），重新提交时清掉
CREATE TABLE ord_change (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                VARCHAR(32)   NOT NULL,           -- BG + yyyyMMdd + 4 位流水
    company_id        BIGINT        NOT NULL REFERENCES bas_company (id),  -- 取订单的
    order_id          BIGINT        NOT NULL REFERENCES ord_order (id),
    change_types      VARCHAR(100)  NOT NULL,           -- 逗号分隔：QTY / DUE / COLOR_SIZE / PRICE / TECH_PACK / CANCEL，由改动算出来
    reason            VARCHAR(500)  NOT NULL,
    responsible_party VARCHAR(16)   NOT NULL DEFAULT 'SELF',  -- SELF 我方 / FACTORY 工厂 / SUPPLIER 供应商 / CUSTOMER 客户（TRADE）
    fee_note          VARCHAR(500),                     -- 费用说明
    reject_reason     VARCHAR(255),                     -- 最近一次驳回的原因；动作改
    status            VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PENDING / APPROVED / VOIDED；动作改
    audit_by          BIGINT,
    audit_time        TIMESTAMPTZ,
    version           INT           NOT NULL DEFAULT 0,
    remark            VARCHAR(500),
    create_by         BIGINT,
    create_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_ord_change_no ON ord_change (no);
CREATE INDEX idx_ord_change_order ON ord_change (order_id);
CREATE INDEX idx_ord_change_company ON ord_change (company_id);
-- 一张订单同时最多一张没审完的变更单：两个人同时提，后一个被拦住
CREATE UNIQUE INDEX uk_ord_change_open ON ord_change (order_id) WHERE status IN ('DRAFT', 'PENDING');
COMMENT ON TABLE ord_change IS '订单变更单';

-- 4. 变更明细：每一处改动的"原 → 新"。数量、配色配码按 SKU；单价、资料包版本按款；交期是单头的要求交期
CREATE TABLE ord_change_item (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    change_id         BIGINT        NOT NULL REFERENCES ord_change (id),
    seq               INT           NOT NULL,
    change_type       VARCHAR(16)   NOT NULL,           -- QTY / DUE / COLOR_SIZE / PRICE / TECH_PACK
    order_item_id     BIGINT        REFERENCES ord_order_item (id),  -- 改的是哪一行；加新 SKU 时为空
    style_id          BIGINT        REFERENCES sty_style (id),
    style_sku_id      BIGINT        REFERENCES sty_style_sku (id),
    from_qty          INT,                              -- 加 SKU 时为 0
    to_qty            INT,                              -- 减掉一个 SKU 时为 0
    from_date         DATE,
    to_date           DATE,
    from_price        NUMERIC(14,4),                    -- 这个款原来的单价；按尺码不一样时为空
    to_price          NUMERIC(14,4),                    -- 新单价；新加的 SKU 也记它用的单价
    from_tech_pack_id BIGINT        REFERENCES tp_tech_pack (id),
    to_tech_pack_id   BIGINT        REFERENCES tp_tech_pack (id)
);
CREATE INDEX idx_ord_change_item_change ON ord_change_item (change_id, seq);
CREATE INDEX idx_ord_change_item_order_item ON ord_change_item (order_item_id);
CREATE INDEX idx_ord_change_item_style ON ord_change_item (style_id);
CREATE INDEX idx_ord_change_item_sku ON ord_change_item (style_sku_id);
CREATE INDEX idx_ord_change_item_from_pack ON ord_change_item (from_tech_pack_id);
CREATE INDEX idx_ord_change_item_to_pack ON ord_change_item (to_tech_pack_id);
COMMENT ON TABLE ord_change_item IS '订单变更明细';
