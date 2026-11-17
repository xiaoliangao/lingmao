-- V25：外发加工单、成品采购单、进度节点、报工、异常、回货（docs/api/05、PRD §6.5.5、§6.7，数据库设计 §14）。
-- 菜单、按钮权限、角色授权 V17 已经建好；字典 process_mode、prod_node、issue_type 在 V11，prod_terms 在 V17。
-- 两种单一张表，按 kind 分（架构 §4.10）。生产单草稿就占着订单行的已排产（ord_order_item.scheduled_qty），删、作废时退回。
-- 节点状态、货期预警、当前节点、已回货合计都现算，不存。
-- 写法见 V10 开头。

-- 1. 生产单：一个加工厂 + 一个款式（PRD §6.5.5）
CREATE TABLE prd_order (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                  VARCHAR(32)   NOT NULL,           -- JG 外发加工单 / CP 成品采购单 + yyyyMMdd + 4 位流水
    company_id          BIGINT        NOT NULL REFERENCES bas_company (id),
    kind                VARCHAR(16)   NOT NULL,           -- OUTSOURCE 外发加工单（清加工、半包）/ BUY 成品采购单（包工包料）；排单时定，不能改
    factory_id          BIGINT        NOT NULL REFERENCES sup_supplier (id),  -- 加工厂：合作中的成衣厂或加工厂
    process_mode        VARCHAR(64)   NOT NULL,           -- 字典 process_mode：外发加工单是清加工、半包，成品采购单是 FULL
    order_id            BIGINT        NOT NULL REFERENCES ord_order (id),
    style_id            BIGINT        NOT NULL REFERENCES sty_style (id),
    tech_pack_id        BIGINT        NOT NULL REFERENCES tp_tech_pack (id),  -- 订单锁定的生产 BOM
    order_date          DATE          NOT NULL,           -- 下单日期：排单那天
    due_date            DATE          NOT NULL,           -- 交期，默认 = 承诺交期 − 10 天
    expect_date         DATE,                             -- 预计交期：上报异常填了新交期时改，原交期不动；动作改
    unit_price          NUMERIC(14,4) NOT NULL CHECK (unit_price > 0),  -- 元 / 件：外发加工单是加工费，成品采购单是成品单价（含料）
    price_source_id     BIGINT        REFERENCES prc_price (id),  -- 单价取自哪条价格档案；手填的为空
    price_change_reason VARCHAR(255),                     -- 和档案现价不一样时必填，审核时记进价格档案（Q25）
    tax_pct             NUMERIC(6,2)  NOT NULL,           -- 税率 %，默认带加工厂
    tax_included        BOOLEAN       NOT NULL DEFAULT true,
    total_qty           INT           NOT NULL DEFAULT 0, -- 件数合计，排单时算好（明细不能改）
    total_amount        NUMERIC(14,2) NOT NULL DEFAULT 0, -- 件数 × 单价；多级审批按它加级
    merch_id            BIGINT        NOT NULL REFERENCES sys_user (id),  -- 生产跟单；数据范围"本人"按制单人、生产跟单；手机"我的跟单"按它
    settle_method       VARCHAR(64)   NOT NULL,           -- 字典 settle_method，默认带加工厂
    terms               TEXT,                             -- 条款：质量要求、延期扣款、溢短装
    issue_status        VARCHAR(16),                      -- 领料状态 NONE / PARTIAL / DONE；资料包里没有我方采购的料时为空（不用领料）；领料、退料过账时回写
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT / PENDING / APPROVED 已下单 / RUNNING 生产中 / DONE 已完成 / CLOSED / VOIDED；动作改
    audit_by            BIGINT,
    audit_time          TIMESTAMPTZ,
    version             INT           NOT NULL DEFAULT 0,
    remark              VARCHAR(500),
    create_by           BIGINT,
    create_time         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by           BIGINT,
    update_time         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_prd_order_no ON prd_order (no);
CREATE INDEX idx_prd_order_kind ON prd_order (kind, status);       -- 两个菜单的列表
CREATE INDEX idx_prd_order_company ON prd_order (company_id);
CREATE INDEX idx_prd_order_factory ON prd_order (factory_id);
CREATE INDEX idx_prd_order_order ON prd_order (order_id);
CREATE INDEX idx_prd_order_style ON prd_order (style_id);
CREATE INDEX idx_prd_order_pack ON prd_order (tech_pack_id);
CREATE INDEX idx_prd_order_merch ON prd_order (merch_id);
CREATE INDEX idx_prd_order_price ON prd_order (price_source_id);
CREATE INDEX idx_prd_order_create_by ON prd_order (create_by);
COMMENT ON TABLE prd_order IS '生产单（外发加工单、成品采购单）';

-- 2. 生产单明细：一行一个款式 SKU，排单时定，之后件数不改（要改就删草稿重新排单）
CREATE TABLE prd_order_item (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prd_order_id  BIGINT        NOT NULL REFERENCES prd_order (id),
    seq           INT           NOT NULL,
    order_item_id BIGINT        NOT NULL REFERENCES ord_order_item (id),  -- 来源订单行：回写已排产、已回货
    style_sku_id  BIGINT        NOT NULL REFERENCES sty_style_sku (id),
    qty           INT           NOT NULL CHECK (qty > 0),
    returned_qty  INT           NOT NULL DEFAULT 0,  -- 已回货（含次品），回货单回写；动作改
    defect_qty    INT           NOT NULL DEFAULT 0,  -- 其中次品；动作改
    CHECK (returned_qty >= 0 AND defect_qty >= 0 AND defect_qty <= returned_qty)
);
CREATE INDEX idx_prd_item_order ON prd_order_item (prd_order_id, seq);
CREATE UNIQUE INDEX uk_prd_item_sku ON prd_order_item (prd_order_id, style_sku_id);
CREATE INDEX idx_prd_item_order_item ON prd_order_item (order_item_id);
CREATE INDEX idx_prd_item_sku ON prd_order_item (style_sku_id);
COMMENT ON TABLE prd_order_item IS '生产单明细';

-- 3. 进度节点：最后一级审核时按字典 prod_node 从交期往前倒排生成，反审核删掉
CREATE TABLE prd_node (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    prd_order_id     BIGINT        NOT NULL REFERENCES prd_order (id),
    seq              INT           NOT NULL,
    node_code        VARCHAR(64)   NOT NULL,           -- 字典 prod_node 的 value；CUT 裁剪的计划开始就是要料日期
    node_name        VARCHAR(64)   NOT NULL,           -- 生成时的名字，字典以后改名、删了也看得懂
    plan_start       DATE          NOT NULL,
    plan_finish      DATE          NOT NULL,
    actual_start     DATE,                             -- 最近一次报的
    actual_finish    DATE,
    done_qty         INT,
    last_report_by   BIGINT,                           -- 最近汇报人，同审计人不加外键
    last_report_time TIMESTAMPTZ,
    remark           VARCHAR(500),
    update_by        BIGINT,
    update_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (plan_finish >= plan_start)
);
CREATE UNIQUE INDEX uk_prd_node ON prd_node (prd_order_id, node_code);
CREATE INDEX idx_prd_node_seq ON prd_node (prd_order_id, seq);
CREATE INDEX idx_prd_node_late ON prd_node (plan_finish) WHERE actual_finish IS NULL;   -- 节点延期
COMMENT ON TABLE prd_node IS '生产单进度节点';

-- 4. 报工记录：每次报进度一条，只插不改；照片挂 biz_attachment（PRODUCTION_NODE_REPORT）
CREATE TABLE prd_node_report (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    node_id       BIGINT        NOT NULL REFERENCES prd_node (id),
    prd_order_id  BIGINT        NOT NULL REFERENCES prd_order (id),   -- 冗余，按单查
    action        VARCHAR(16)   NOT NULL,           -- START 只报开始 / FINISH 报完成
    actual_start  DATE,
    actual_finish DATE,
    done_qty      INT,
    remark        VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_prd_report_node ON prd_node_report (node_id, id);
CREATE INDEX idx_prd_report_order ON prd_node_report (prd_order_id);
COMMENT ON TABLE prd_node_report IS '报工记录';

-- 5. 异常：不是单据，没有审核、作废；照片挂 biz_attachment（PRODUCTION_ISSUE）
CREATE TABLE prd_issue (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                VARCHAR(32)   NOT NULL,           -- YC + yyyyMMdd + 4 位流水
    prd_order_id      BIGINT        NOT NULL REFERENCES prd_order (id),
    issue_type        VARCHAR(64)   NOT NULL,           -- 字典 issue_type
    description       VARCHAR(1000) NOT NULL,
    impact_delivery   BOOLEAN       NOT NULL DEFAULT false,
    new_expect_date   DATE,                             -- 填了就改生产单的预计交期
    responsible_party VARCHAR(16),                      -- SELF / FACTORY / SUPPLIER / CUSTOMER（TRADE）；空 = 待定
    measure           VARCHAR(1000),                    -- 处理措施，关闭时必填
    handler_id        BIGINT        REFERENCES sys_user (id),  -- 点"开始处理"的人
    status            VARCHAR(16)   NOT NULL DEFAULT 'OPEN',   -- OPEN 待处理 / HANDLING 处理中 / CLOSED 已关闭；动作改
    close_by          BIGINT,
    close_time        TIMESTAMPTZ,
    create_by         BIGINT,                           -- 上报人
    create_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_prd_issue_no ON prd_issue (no);
CREATE INDEX idx_prd_issue_order ON prd_issue (prd_order_id);
CREATE INDEX idx_prd_issue_open ON prd_issue (prd_order_id) WHERE status <> 'CLOSED';
CREATE INDEX idx_prd_issue_handler ON prd_issue (handler_id);
COMMENT ON TABLE prd_issue IS '生产异常';

-- 6. 回货单："登记"一步 = 建 + 审核；作废一步完成（回退已回货再作废），原因、谁、什么时候记在单上。
-- 一期不记成品库存（Q01、Q44），收货仓只记录
CREATE TABLE prd_receipt (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no           VARCHAR(32)   NOT NULL,           -- HH + yyyyMMdd + 4 位流水
    company_id   BIGINT        NOT NULL REFERENCES bas_company (id),  -- 取生产单的
    prd_order_id BIGINT        NOT NULL REFERENCES prd_order (id),
    receipt_date DATE          NOT NULL,           -- 实际回货日期，不能晚于今天
    warehouse_id BIGINT        REFERENCES bas_warehouse (id),  -- 收货仓（成品仓），只记录
    logistics_no VARCHAR(64),
    status       VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT（一期用不到）/ APPROVED 已登记 / VOIDED 已作废；动作改
    void_reason  VARCHAR(255),                     -- 作废原因；动作改
    void_by      BIGINT,
    void_time    TIMESTAMPTZ,
    audit_by     BIGINT,
    audit_time   TIMESTAMPTZ,
    version      INT           NOT NULL DEFAULT 0,
    remark       VARCHAR(500),
    create_by    BIGINT,
    create_time  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by    BIGINT,
    update_time  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_prd_receipt_no ON prd_receipt (no);
CREATE INDEX idx_prd_receipt_order ON prd_receipt (prd_order_id);
CREATE INDEX idx_prd_receipt_company ON prd_receipt (company_id);
CREATE INDEX idx_prd_receipt_wh ON prd_receipt (warehouse_id);
CREATE INDEX idx_prd_receipt_date ON prd_receipt (receipt_date);   -- 看板"本月回货"
COMMENT ON TABLE prd_receipt IS '回货单';

-- 7. 回货明细
CREATE TABLE prd_receipt_item (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    receipt_id        BIGINT        NOT NULL REFERENCES prd_receipt (id),
    seq               INT           NOT NULL,
    prd_order_item_id BIGINT        NOT NULL REFERENCES prd_order_item (id),
    style_sku_id      BIGINT        NOT NULL REFERENCES sty_style_sku (id),   -- 冗余
    qty               INT           NOT NULL CHECK (qty > 0),               -- 本次回货，含次品
    defect_qty        INT           NOT NULL DEFAULT 0,
    remark            VARCHAR(255),
    CHECK (defect_qty >= 0 AND defect_qty <= qty)
);
CREATE INDEX idx_prd_receipt_item ON prd_receipt_item (receipt_id, seq);
CREATE INDEX idx_prd_receipt_item_line ON prd_receipt_item (prd_order_item_id);
CREATE INDEX idx_prd_receipt_item_sku ON prd_receipt_item (style_sku_id);
COMMENT ON TABLE prd_receipt_item IS '回货明细';
