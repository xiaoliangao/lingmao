-- V20：资料包（docs/api/01、架构 §4.7 §4.9，数据库设计 §9）和它要用的系统设置表 sys_config（§3.9）。
-- 一个版本 = 一行 tp_tech_pack + 四个页签的明细：尺寸指示、物料清单（BOM 行、配料、分码用量）、工艺指示（分段文字、工序工价和图示）、
-- 核价的其他费用。草稿保存整版覆盖，生效后不再改（修订复制出新草稿），所以明细没有审计列。
-- 工艺指示的插图挂 biz_attachment（TECH_PACK，category = 段代码）；工艺标准文件挂 biz_attachment（CRAFT_STANDARD，doc_id = 0）。
-- 写法见 V10 开头。

-- 1. 系统设置：非字典、非审批规则的参数。一期放工艺指示各段的默认文字 techpack.craft_defaults（{段代码: 文字}）
--    和新建资料包的默认包装方式 techpack.packing_mode（字典 packing_mode 的值）。密钥不进这张表
CREATE TABLE sys_config (
    config_key   VARCHAR(64)  PRIMARY KEY,
    config_value JSONB        NOT NULL,
    remark       VARCHAR(255),
    update_by    BIGINT,
    update_time  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE sys_config IS '系统设置';

-- 2. 资料包版本：一个款式一个阶段的一个版本。version 是版本号（V1、V2），乐观锁叫 version_lock
CREATE TABLE tp_tech_pack (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    style_id       BIGINT        NOT NULL REFERENCES sty_style (id),  -- 二期研发 BOM 挂样衣时放宽成可空（架构 §4.7）
    stage          VARCHAR(16)   NOT NULL,           -- DEV 研发 / PROD 生产；二期加 ORDER 生产单资料包
    version        INT           NOT NULL,           -- 版本号，每个阶段各自从 1 起
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT 草稿 / EFFECTIVE 已生效 / VOIDED 已作废；动作改
    version_note   VARCHAR(500),                     -- 本版说明，V2 起生效前必填
    source_pack_id BIGINT        REFERENCES tp_tech_pack (id),  -- 从哪个版本复制来的（转生产 BOM、修订）
    util_pct       NUMERIC(6,2),                     -- 排版利用率 %，只记录
    packing_mode   VARCHAR(64),                      -- 包装方式，字典 packing_mode
    cost_mode      VARCHAR(16)   NOT NULL DEFAULT 'OUTSOURCE',  -- 核价按什么算：OUTSOURCE 按外发 / BUY 按成品采购
    buy_price      NUMERIC(14,4),                    -- 成品单价，元/件（含料），按成品采购算时用
    publish_by     BIGINT,                           -- 生效人；动作改，同审计人不加外键
    publish_time   TIMESTAMPTZ,                      -- 生效时间；动作改
    version_lock   INT           NOT NULL DEFAULT 0, -- 乐观锁：保存草稿、生效都 +1
    create_by      BIGINT,
    create_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by      BIGINT,
    update_time    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
-- 下面三个唯一索引都限定 DEV、PROD：二期生产单资料包同一个款会有很多份、各自生效，不受它们管
CREATE INDEX idx_tp_pack_style ON tp_tech_pack (style_id);  -- 外键用：带条件的索引不算外键的索引
CREATE UNIQUE INDEX uk_tp_pack_version ON tp_tech_pack (style_id, stage, version) WHERE stage IN ('DEV', 'PROD');
-- 同款同阶段只有一个已生效：新版本生效时先把旧的改成已作废
CREATE UNIQUE INDEX uk_tp_pack_effective ON tp_tech_pack (style_id, stage) WHERE status = 'EFFECTIVE' AND stage IN ('DEV', 'PROD');
-- 同款同阶段最多一个草稿：两个人同时点"建生产 BOM""修订"，后一个被拦住（1000）
CREATE UNIQUE INDEX uk_tp_pack_draft ON tp_tech_pack (style_id, stage) WHERE status = 'DRAFT' AND stage IN ('DEV', 'PROD');
CREATE INDEX idx_tp_pack_source ON tp_tech_pack (source_pack_id);
COMMENT ON TABLE tp_tech_pack IS '资料包';

-- 3. 尺寸指示：一个部位一行，部位 × 尺码的尺寸另一张表（没填的格子不存行）
CREATE TABLE tp_spec_point (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tech_pack_id BIGINT        NOT NULL REFERENCES tp_tech_pack (id),
    seq          INT           NOT NULL,             -- 页面上拖动的顺序，从 1 起
    point        VARCHAR(64)   NOT NULL,             -- 部位，存文字不存字典 value：手填的也能存，字典改名不影响
    method       VARCHAR(500),                       -- 测量方法
    tolerance_cm NUMERIC(8,2),                       -- 公差 ±cm
    grade_cm     NUMERIC(8,2),                       -- 档差 cm；按档差生成在前端算，存的还是每格的数
    remark       VARCHAR(255)
);
CREATE INDEX idx_tp_spec_point_pack ON tp_spec_point (tech_pack_id, seq);
COMMENT ON TABLE tp_spec_point IS '资料包尺寸指示';

CREATE TABLE tp_spec_value (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    spec_point_id BIGINT        NOT NULL REFERENCES tp_spec_point (id),
    size_code     VARCHAR(16)   NOT NULL,
    value_cm      NUMERIC(8,2)  NOT NULL
);
CREATE UNIQUE INDEX uk_tp_spec_value ON tp_spec_value (spec_point_id, size_code);
COMMENT ON TABLE tp_spec_value IS '资料包尺寸指示的尺寸';

-- 4. 物料清单：一行 = 一个部位用的一种物料；用哪个物料 SKU 在 tp_bom_item_sku，分码用量在 tp_bom_item_size
CREATE TABLE tp_bom_item (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tech_pack_id           BIGINT        NOT NULL REFERENCES tp_tech_pack (id),
    seq                    INT           NOT NULL,
    part                   VARCHAR(64)   NOT NULL,   -- 部位，字典 bom_part
    material_id            BIGINT        NOT NULL REFERENCES bas_material (id),
    sku_mode               VARCHAR(16)   NOT NULL DEFAULT 'FIXED',    -- FIXED 固定 / BY_COLOR 随款色 / BY_SIZE 随尺码 / BY_COLOR_SIZE 随款色和尺码
    usage_mode             VARCHAR(16)   NOT NULL DEFAULT 'UNIFORM',  -- UNIFORM 统一 / BY_SIZE 分码
    usage_qty              NUMERIC(14,6),            -- 统一用量，库存单位；分码时为空
    unit                   VARCHAR(64)   NOT NULL,   -- 物料的库存单位，存一份
    loss_pct               NUMERIC(6,2)  NOT NULL DEFAULT 0,  -- 损耗率 %，3 表示 3%
    source                 VARCHAR(32)   NOT NULL DEFAULT 'PURCHASE',  -- PURCHASE 我方采购 / FACTORY 工厂自备 / CUSTOMER_SUPPLIED 客供
    supplier_id            BIGINT        REFERENCES sup_supplier (id),
    ref_price              NUMERIC(14,4),            -- 参考单价，元/库存单位（价格档案、物料单价按采购单位，带过来时除以换算系数）
    price_source_id        BIGINT        REFERENCES prc_price (id),  -- 默认单价取自哪条价格档案；手改了也留着
    price_tax_included     BOOLEAN       NOT NULL DEFAULT true,
    price_freight_included BOOLEAN       NOT NULL DEFAULT true,
    remark                 VARCHAR(255)
);
CREATE INDEX idx_tp_bom_item_pack ON tp_bom_item (tech_pack_id, seq);
CREATE INDEX idx_tp_bom_item_material ON tp_bom_item (material_id);
CREATE INDEX idx_tp_bom_item_supplier ON tp_bom_item (supplier_id);
CREATE INDEX idx_tp_bom_item_price ON tp_bom_item (price_source_id);
COMMENT ON TABLE tp_bom_item IS '资料包物料清单';

-- 配料：固定 1 条；随款色每个款色 1 条；随尺码每个尺码 1 条；随款色和尺码每个款色 × 尺码 1 条
CREATE TABLE tp_bom_item_sku (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bom_item_id     BIGINT        NOT NULL REFERENCES tp_bom_item (id),
    color_id        BIGINT        REFERENCES bas_color (id),  -- 款色；固定、随尺码的为空
    size_code       VARCHAR(16),                     -- 尺码；固定、随款色的为空
    material_sku_id BIGINT        NOT NULL REFERENCES bas_material_sku (id),
    ref_price       NUMERIC(14,4)                    -- 这个 SKU 单独的参考单价，元/库存单位；空 = 用行上的
);
CREATE UNIQUE INDEX uk_tp_bom_sku ON tp_bom_item_sku (bom_item_id, COALESCE(color_id, 0), COALESCE(size_code, ''));
CREATE INDEX idx_tp_bom_sku_sku ON tp_bom_item_sku (material_sku_id);
CREATE INDEX idx_tp_bom_sku_color ON tp_bom_item_sku (color_id);
COMMENT ON TABLE tp_bom_item_sku IS '资料包物料清单的配料';

CREATE TABLE tp_bom_item_size (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bom_item_id BIGINT        NOT NULL REFERENCES tp_bom_item (id),
    size_code   VARCHAR(16)   NOT NULL,
    usage_qty   NUMERIC(14,6) NOT NULL               -- 单件用量，库存单位
);
CREATE UNIQUE INDEX uk_tp_bom_size ON tp_bom_item_size (bom_item_id, size_code);
COMMENT ON TABLE tp_bom_item_size IS '资料包物料清单的分码用量';

-- 5. 工艺指示：一段一行，空段可以不存。段代码是枚举（PDF 的版式按它排）：
--    CUTTING SEWING PART QUALITY（车间缝制）、BUTTON IRONING LABEL PACKING（后道）、NOTE（注意事项）
CREATE TABLE tp_craft_section (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tech_pack_id BIGINT        NOT NULL REFERENCES tp_tech_pack (id),
    section      VARCHAR(16)   NOT NULL,
    content      TEXT          NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX uk_tp_craft_section ON tp_craft_section (tech_pack_id, section);
COMMENT ON TABLE tp_craft_section IS '资料包工艺指示';

-- 6. 工序工价（工艺指示页签最下面，内部）。图示跟着工序行整版覆盖，所以不挂 biz_attachment
CREATE TABLE tp_process (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tech_pack_id BIGINT        NOT NULL REFERENCES tp_tech_pack (id),
    seq          INT           NOT NULL,
    category     VARCHAR(64),                        -- 工序类别，字典 process_category
    name         VARCHAR(100)  NOT NULL,
    requirement  VARCHAR(1000),                      -- 工艺要求
    std_seconds  INT,                                -- 标准工时，秒
    price        NUMERIC(14,4),                      -- 工价，元/件
    remark       VARCHAR(255)
);
CREATE INDEX idx_tp_process_pack ON tp_process (tech_pack_id, seq);
COMMENT ON TABLE tp_process IS '资料包工序工价';

CREATE TABLE tp_process_image (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_id BIGINT        NOT NULL REFERENCES tp_process (id),
    sort       INT           NOT NULL DEFAULT 0,
    file_url   VARCHAR(255)  NOT NULL,               -- 不带签名的 /files/...
    thumb_url  VARCHAR(255)
);
CREATE INDEX idx_tp_process_image ON tp_process_image (process_id, sort);
COMMENT ON TABLE tp_process_image IS '资料包工序图示';

-- 7. 核价的其他费用：BOM 以外按件摊的钱（印绣花外加工、运费、包装）。加工费不在这里：按外发取工序工价合计，按成品采购取 buy_price
CREATE TABLE tp_cost_item (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tech_pack_id BIGINT        NOT NULL REFERENCES tp_tech_pack (id),
    seq          INT           NOT NULL,
    name         VARCHAR(100)  NOT NULL,
    amount       NUMERIC(14,4) NOT NULL,             -- 元/件
    remark       VARCHAR(255)
);
CREATE INDEX idx_tp_cost_item_pack ON tp_cost_item (tech_pack_id, seq);
COMMENT ON TABLE tp_cost_item IS '资料包核价的其他费用';
