-- V14：款式（RFC 005 §2，数据库设计 §8.1–§8.3）。指向 V10 的品类、尺码组、客户、颜色，V12 的供应商，V13 的物料，
-- 所以排在 basic 两个文件和 supplier 之后。图片、设计附件挂 biz_attachment（STYLE，分 IMAGE、DESIGN），不另建表。
-- 写法见 V10 开头。

-- 1. 款式：下单、生产、装箱取数的唯一来源（PRD §6.2）
CREATE TABLE sty_style (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code               VARCHAR(32)   NOT NULL,           -- 款号：自动编号（大类 + yyMMdd + 设计师编号 + 2 位）或手填；导入的照用老款号；只在插入时写
    name               VARCHAR(100)  NOT NULL,
    alias_name         VARCHAR(100),                     -- 花名：俗称、别名，不查重（Q43）；列表能搜
    style_class        VARCHAR(64)   NOT NULL,           -- 大类，字典 style_class，value 就是款号开头的 2 位字母
    brand              VARCHAR(64)   NOT NULL,           -- 字典 brand
    category_id        BIGINT        NOT NULL REFERENCES bas_category (id),  -- 品类，只能选启用的叶子（Service 调 basic 查）
    year               SMALLINT      NOT NULL,           -- 2000-2099
    season             VARCHAR(64)   NOT NULL,           -- 字典 season
    wave               VARCHAR(64),                      -- 字典 wave
    style_tag          VARCHAR(64),                      -- 风格，字典 style_tag
    series             VARCHAR(64),                      -- 字典 series
    gender             VARCHAR(64),                      -- 字典 gender
    designer_id        BIGINT        REFERENCES sys_user (id),  -- 设计师；自动编号时必填
    pattern_maker_id   BIGINT        REFERENCES sys_user (id),  -- 版师
    source             VARCHAR(64)   NOT NULL,           -- 来源，字典 style_source；CUSTOMER_SAMPLE 客户来样 TRADE 关时拒收
    customer_id        BIGINT        REFERENCES bas_customer (id),  -- TRADE 留口子，关着时必须为空
    customer_style_no  VARCHAR(64),                      -- 客户款号，同上
    sample_no          VARCHAR(32),                      -- 样衣编号：导入时照填，二期转大货时自动带
    size_group_id      BIGINT        NOT NULL REFERENCES bas_size_group (id),  -- 已经有 SKU 的不能换（RFC 005 §5）
    main_material_id   BIGINT        REFERENCES bas_material (id),  -- 主力面料，只当默认值
    product_name       VARCHAR(50),                      -- 吊牌品名，如 风衣
    composition        VARCHAR(255),                     -- 吊牌成分
    exec_standard      VARCHAR(64),                      -- 执行标准，字典 exec_standard
    safety_category    VARCHAR(64),                      -- 安全类别，字典 safety_category
    tag_price          NUMERIC(14,2),                    -- 吊牌价
    ref_cost           NUMERIC(14,4),                    -- 参考成本价，元/件，手填；资料包算出来的不写回
    default_factory_id BIGINT        REFERENCES sup_supplier (id),  -- 默认加工厂，排单时带出；只当默认值，不查状态
    launch_date        DATE,                             -- 上市日期
    status             VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',  -- DRAFT 草稿 / ENABLED 启用 / DISABLED 停用；动作改
    tech_pack_status   VARCHAR(16)   NOT NULL DEFAULT 'NONE',   -- 资料包状态 NONE 无 / DEV 研发中 / PROD 生产 BOM 已生效；由资料包回写（第 3 批）
    remark             VARCHAR(500),
    legacy_attrs       JSONB,                            -- 领猫附加属性（等级、版型库单号、财务成本价……），只读显示
    create_by          BIGINT,
    create_time        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by          BIGINT,
    update_time        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_sty_style_code ON sty_style (code);
CREATE INDEX idx_sty_style_category ON sty_style (category_id);
CREATE INDEX idx_sty_style_designer ON sty_style (designer_id);
CREATE INDEX idx_sty_style_pattern_maker ON sty_style (pattern_maker_id);
CREATE INDEX idx_sty_style_size_group ON sty_style (size_group_id);
CREATE INDEX idx_sty_style_factory ON sty_style (default_factory_id);
CREATE INDEX idx_sty_style_customer ON sty_style (customer_id);
CREATE INDEX idx_sty_style_main_mat ON sty_style (main_material_id);
CREATE INDEX idx_sty_style_season ON sty_style (year, season);         -- 列表按年份、季节筛
COMMENT ON TABLE sty_style IS '款式';

-- 2. 款式颜色：选了哪些颜色、顺序、停用了没有。只追加；生成过 SKU 的颜色只能停用（Service 查 sty_style_sku）。
--    跟着款式整体改，没有审计列
CREATE TABLE sty_style_color (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    style_id BIGINT   NOT NULL REFERENCES sty_style (id),
    color_id BIGINT   NOT NULL REFERENCES bas_color (id),
    seq      INT      NOT NULL,                          -- 顺序，能拖
    status   SMALLINT NOT NULL DEFAULT 1                 -- 停用的颜色不能再下单；不连带停用它的 SKU
);
-- 同时给一款加同一个颜色，后一个被拦住（1000"已经有这个颜色了"）
CREATE UNIQUE INDEX uk_sty_style_color ON sty_style_color (style_id, color_id);
CREATE INDEX idx_sty_style_color_color ON sty_style_color (color_id);
COMMENT ON TABLE sty_style_color IS '款式颜色';

-- 3. 款式 SKU：款式 × 颜色 × 尺码，需求订单、生产单、回货都落在它上面。
--    颜色指向 bas_color，不指向 sty_style_color（RFC 005 §5）；尺码存 size_code 字符串，不存尺码的 id
CREATE TABLE sty_style_sku (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    style_id    BIGINT        NOT NULL REFERENCES sty_style (id),
    code        VARCHAR(64)   NOT NULL,                  -- 款号-颜色码-尺码，生成时拼好、不能改
    color_id    BIGINT        NOT NULL REFERENCES bas_color (id),
    size_code   VARCHAR(16)   NOT NULL,                  -- 尺码组里的尺码
    barcode     VARCHAR(32),                             -- 条码，手填或导入
    tag_price   NUMERIC(14,2),                           -- SKU 吊牌价；空 = 用款式的（大码加价时填）
    status      SMALLINT      NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_sty_sku_code ON sty_style_sku (code);
-- 一键生成用 ON CONFLICT (style_id, color_id, size_code) DO NOTHING 只补缺，调几次结果都一样；第一列也给外键用
CREATE UNIQUE INDEX uk_sty_sku_combo ON sty_style_sku (style_id, color_id, size_code);
CREATE UNIQUE INDEX uk_sty_sku_barcode ON sty_style_sku (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX idx_sty_sku_color ON sty_style_sku (color_id);
COMMENT ON TABLE sty_style_sku IS '款式 SKU';
