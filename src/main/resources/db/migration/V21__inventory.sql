-- V21：库存（docs/api/04、架构 §5，数据库设计 §13）：物料入库单 / 出库单（一张表按 kind 分，架构 §4.10）、库存、库存日志、物料盘点单。
-- 库存只能由入库单、出库单、盘点单审核改（架构 §5.1）；过账用条件更新 qty + 变动 >= 0，不允许负库存（Q08）。
-- 过过账的单不能删、只能作废（用户 11-17 定，RFC 008 §3.2）：库存日志指向单据和明细的外键留着，删不掉就是保护。
-- 物权 owner_type / owner_id、货位 location_id 是 TRADE 和二期货位的口子，一期固定 'OWN' / 0 / 0（数据库设计 §1.5、§13.3）。
-- 写法见 V10 开头。

-- 1. 物料入库单、物料出库单
CREATE TABLE inv_doc (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no                   VARCHAR(32)   NOT NULL,           -- RK 入库单 / CK 出库单 + yyyyMMdd + 4 位流水
    company_id           BIGINT        NOT NULL REFERENCES bas_company (id),
    kind                 VARCHAR(8)    NOT NULL,           -- IN 物料入库单 / OUT 物料出库单；建单时定，不能改
    doc_type             VARCHAR(16)   NOT NULL,           -- PURCHASE_IN / RETURN_IN / OTHER_IN / CUSTOMER_IN（TRADE）；ISSUE_OUT / PURCHASE_RETURN / TRANSFER / OTHER_OUT
    status               VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT 草稿 / APPROVED 已审核（审核即过账）/ VOIDED 已作废；动作改
    source_type          VARCHAR(32),                      -- 源单：PURCHASE_ORDER / PRODUCTION_ORDER；类型 + id，没有外键
    source_id            BIGINT,
    source_no            VARCHAR(32),                      -- 源单号，冗余显示
    from_warehouse_id    BIGINT        REFERENCES bas_warehouse (id),  -- 出仓：出库单、移库（领料、退料、调拨）有
    to_warehouse_id      BIGINT        REFERENCES bas_warehouse (id),  -- 入仓：入库单、移库有
    partner_id           BIGINT        REFERENCES sup_supplier (id),   -- 往来：供应商（采购的两种）、加工厂（领料、退料）
    customer_id          BIGINT        REFERENCES bas_customer (id),   -- 客供入库用，TRADE 留口子
    doc_date             DATE          NOT NULL,           -- 实际发生日期，不能晚于今天
    reason               VARCHAR(64),                      -- 其他入库 / 其他出库：字典 stock_in_reason / stock_out_reason，必填；别的是选填的一行字
    supplier_delivery_no VARCHAR(64),                      -- 供应商送货单号，以后对账按它认
    logistics_no         VARCHAR(64),
    audit_by             BIGINT,
    audit_time           TIMESTAMPTZ,
    version              INT           NOT NULL DEFAULT 0, -- 乐观锁：保存草稿、每个动作都 +1
    remark               VARCHAR(500),
    create_by            BIGINT,
    create_time          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by            BIGINT,
    update_time          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_inv_doc_no ON inv_doc (no);
CREATE INDEX idx_inv_doc_kind ON inv_doc (kind, status);          -- 两个菜单的列表
CREATE INDEX idx_inv_doc_source ON inv_doc (source_type, source_id);
CREATE INDEX idx_inv_doc_company ON inv_doc (company_id);
CREATE INDEX idx_inv_doc_from ON inv_doc (from_warehouse_id);
CREATE INDEX idx_inv_doc_to ON inv_doc (to_warehouse_id);
CREATE INDEX idx_inv_doc_partner ON inv_doc (partner_id, supplier_delivery_no);
CREATE INDEX idx_inv_doc_customer ON inv_doc (customer_id);
CREATE INDEX idx_inv_doc_create_by ON inv_doc (create_by);        -- 数据范围"本人"按制单人筛
COMMENT ON TABLE inv_doc IS '物料出入库单';

-- 2. 入库、出库明细。审核过的行被库存日志指着：反审核回草稿后能改数量、缸号，不能删、不能换物料（只能整张作废）
CREATE TABLE inv_doc_item (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_id            BIGINT        NOT NULL REFERENCES inv_doc (id),
    seq               INT           NOT NULL,
    material_sku_id   BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type        VARCHAR(16)   NOT NULL DEFAULT 'OWN',   -- 物权：OWN 自有 / CUSTOMER 客供（TRADE）
    owner_id          BIGINT        NOT NULL DEFAULT 0,       -- 自有为 0
    qty               NUMERIC(14,3) NOT NULL,                 -- 库存单位，> 0；方向由 kind 和类型定
    purchase_unit     VARCHAR(64),                            -- 采购入库、采购退货：采购单位（字典 unit）
    purchase_qty      NUMERIC(14,3),                          -- 采购单位数量，按它回写采购单
    conversion_factor NUMERIC(14,6),                          -- qty = purchase_qty × conversion_factor
    price             NUMERIC(14,4),                          -- 元 / 库存单位
    amount            NUMERIC(14,2),
    dye_lot           VARCHAR(32),                            -- 缸号：只记录，库存不按它分（Q07）
    rolls             INT,
    source_item_id    BIGINT,                                 -- 源单明细（采购明细）；源单类型不固定，没有外键
    over_reason       VARCHAR(255),                           -- 超领原因
    remark            VARCHAR(255)
);
CREATE INDEX idx_inv_doc_item_doc ON inv_doc_item (doc_id, seq);
CREATE INDEX idx_inv_doc_item_sku ON inv_doc_item (material_sku_id);
CREATE INDEX idx_inv_doc_item_source ON inv_doc_item (source_item_id);
COMMENT ON TABLE inv_doc_item IS '物料出入库明细';

-- 3. 库存：仓库 × SKU × 物权 × 货位。只有过账（StockLedger）写
CREATE TABLE inv_stock (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    warehouse_id    BIGINT        NOT NULL REFERENCES bas_warehouse (id),
    material_sku_id BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type      VARCHAR(16)   NOT NULL DEFAULT 'OWN',
    owner_id        BIGINT        NOT NULL DEFAULT 0,
    location_id     BIGINT        NOT NULL DEFAULT 0,       -- 货位，0 = 不分货位；二期用
    qty             NUMERIC(14,3) NOT NULL DEFAULT 0,       -- 结存，库存单位，不为负（条件更新保证）
    update_time     TIMESTAMPTZ   NOT NULL DEFAULT now()    -- 最近变动时间
);
CREATE UNIQUE INDEX uk_inv_stock ON inv_stock (warehouse_id, material_sku_id, owner_type, owner_id, location_id);
CREATE INDEX idx_inv_stock_sku ON inv_stock (material_sku_id);
COMMENT ON TABLE inv_stock IS '物料库存';

-- 4. 物料盘点单（先建：库存日志要指向它）
CREATE TABLE inv_count (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no           VARCHAR(32)   NOT NULL,                    -- PD + yyyyMMdd + 4 位流水
    company_id   BIGINT        NOT NULL REFERENCES bas_company (id),
    warehouse_id BIGINT        NOT NULL REFERENCES bas_warehouse (id),
    purpose      VARCHAR(16)   NOT NULL DEFAULT 'DAILY',    -- DAILY 日常盘点 / OPENING 期初录入
    scope        VARCHAR(16)   NOT NULL DEFAULT 'ALL',      -- ALL 全部 / CATEGORY 按物料分类 / PICKED 手选物料
    count_date   DATE          NOT NULL,
    counter_id   BIGINT        NOT NULL REFERENCES sys_user (id),
    book_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),      -- 取账面数的时间
    status       VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',    -- DRAFT / PENDING 待审核 / APPROVED 已审核 / VOIDED 已作废（过过账又反审核回来的）
    audit_by     BIGINT,
    audit_time   TIMESTAMPTZ,
    version      INT           NOT NULL DEFAULT 0,
    remark       VARCHAR(500),
    create_by    BIGINT,
    create_time  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by    BIGINT,
    update_time  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_inv_count_no ON inv_count (no);
-- 同一个仓库同时只能有一张没审核的（PRD §6.6.8 规则 4）：建单、反审核撞上就 1000
CREATE UNIQUE INDEX uk_inv_count_open ON inv_count (warehouse_id) WHERE status IN ('DRAFT', 'PENDING');
CREATE INDEX idx_inv_count_warehouse ON inv_count (warehouse_id);   -- 外键用：带条件的索引不算
CREATE INDEX idx_inv_count_company ON inv_count (company_id);
CREATE INDEX idx_inv_count_status ON inv_count (status);
CREATE INDEX idx_inv_count_counter ON inv_count (counter_id);
CREATE INDEX idx_inv_count_create_by ON inv_count (create_by);
COMMENT ON TABLE inv_count IS '物料盘点单';

CREATE TABLE inv_count_item (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    count_id        BIGINT        NOT NULL REFERENCES inv_count (id),
    seq             INT           NOT NULL,
    material_sku_id BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type      VARCHAR(16)   NOT NULL DEFAULT 'OWN',
    owner_id        BIGINT        NOT NULL DEFAULT 0,
    book_qty        NUMERIC(14,3) NOT NULL DEFAULT 0,       -- 账面数：建单（或重新取账面数）时的库存快照；期初录入是 0
    counted_qty     NUMERIC(14,3),                          -- 实盘数；提交时不能为空
    remark          VARCHAR(255)
);
CREATE INDEX idx_inv_count_item ON inv_count_item (count_id, seq);
CREATE UNIQUE INDEX uk_inv_count_item_sku ON inv_count_item (count_id, material_sku_id, owner_type, owner_id);
CREATE INDEX idx_inv_count_item_sku ON inv_count_item (material_sku_id);
COMMENT ON TABLE inv_count_item IS '物料盘点明细';

CREATE TABLE inv_count_category (
    count_id    BIGINT NOT NULL REFERENCES inv_count (id),
    category_id BIGINT NOT NULL REFERENCES bas_material_category (id),
    PRIMARY KEY (count_id, category_id)
);
CREATE INDEX idx_inv_count_category_cat ON inv_count_category (category_id);
COMMENT ON TABLE inv_count_category IS '物料盘点单的分类范围';

-- 5. 库存日志：每次变动一行，只插不改；反审核再记一条反方向的。来源是入库单 / 出库单（和明细），或盘点单（和明细），二选一
CREATE TABLE inv_stock_log (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inv_doc_id      BIGINT        REFERENCES inv_doc (id),
    inv_doc_item_id BIGINT        REFERENCES inv_doc_item (id),
    count_id        BIGINT        REFERENCES inv_count (id),
    count_item_id   BIGINT        REFERENCES inv_count_item (id),
    change_type     VARCHAR(16)   NOT NULL,                 -- 入库单、出库单的 doc_type；盘点 COUNT_GAIN / COUNT_LOSS；期初录入 OPENING
    doc_no          VARCHAR(32)   NOT NULL,
    biz_date        DATE          NOT NULL,                 -- 单据的实际发生日期
    warehouse_id    BIGINT        NOT NULL REFERENCES bas_warehouse (id),
    material_sku_id BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    owner_type      VARCHAR(16)   NOT NULL DEFAULT 'OWN',
    owner_id        BIGINT        NOT NULL DEFAULT 0,
    location_id     BIGINT        NOT NULL DEFAULT 0,
    change_qty      NUMERIC(14,3) NOT NULL,                 -- 入为正、出为负
    balance_qty     NUMERIC(14,3) NOT NULL,                 -- 变动后结存
    dye_lot         VARCHAR(32),
    create_by       BIGINT,
    create_time     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_inv_log_stock ON inv_stock_log (warehouse_id, material_sku_id, id);
CREATE INDEX idx_inv_log_doc ON inv_stock_log (inv_doc_id);
CREATE INDEX idx_inv_log_item ON inv_stock_log (inv_doc_item_id);
CREATE INDEX idx_inv_log_count ON inv_stock_log (count_id);
CREATE INDEX idx_inv_log_count_item ON inv_stock_log (count_item_id);
-- 按缸号查流水；不带条件，外键 material_sku_id 也用它（带条件的索引不算外键的索引）
CREATE INDEX idx_inv_log_sku ON inv_stock_log (material_sku_id, dye_lot);
CREATE INDEX idx_inv_log_date ON inv_stock_log (biz_date);
COMMENT ON TABLE inv_stock_log IS '物料库存日志';

-- 6. 过过账的单只能作废（11-17）：删除的按钮权限也管作废，名字跟着改
UPDATE sys_menu SET name = '删除、作废'
WHERE type = 3 AND perms IN ('inventory:doc:remove', 'inventory:count:remove') AND deleted = false;
