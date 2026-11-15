-- V13：基础资料的第二个文件（RFC 003 §6、§7，数据库设计 §5.9–§5.11）：物料、物料 SKU、仓库。
-- 物料的默认供应商、工厂仓绑定的工厂指向 V12 的 sup_supplier，所以排在供应商之后。这两列只当默认值、只是引用：
-- basic 的 Java 代码不调 supplier（架构 §3.1、A23）。货位（二期）这次不建。写法见 V10 开头。

-- 1. 物料：面料、辅料、包材统一建档，一物一码（PRD §6.1.5）。采购、库存、BOM 落在 SKU 上，这张表放各 SKU 共用的属性
CREATE TABLE bas_material (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                VARCHAR(32)   NOT NULL,          -- 新建 = 分类码 + 4 位（ML0012）；导入的照用领猫编码；建好不改
    name                VARCHAR(200)  NOT NULL,
    category_id         BIGINT        NOT NULL REFERENCES bas_material_category (id),  -- 必须是二级（Service 校验）；建好不改
    material_type       VARCHAR(64)   NOT NULL,          -- 字典 material_type
    stock_unit          VARCHAR(64)   NOT NULL,          -- 库存单位，字典 unit；库存、BOM 用量都按它记，建好不改（RFC 003 §7.2）
    purchase_unit       VARCHAR(64)   NOT NULL,          -- 采购单位，默认等于库存单位；能改（单据存了当时的单位和系数）
    conversion_factor   NUMERIC(14,6) NOT NULL DEFAULT 1,  -- 1 采购单位 = n 库存单位，> 0
    width_cm            NUMERIC(8,2),                    -- 门幅 cm
    weight_gsm          NUMERIC(8,2),                    -- 克重 g/㎡
    composition         VARCHAR(255),                    -- 成分
    texture             VARCHAR(64),                     -- 组织、纹理
    default_supplier_id BIGINT        REFERENCES sup_supplier (id),  -- 默认供应商：只当默认值，保存时不查状态（A23）
    supplier_item_no    VARCHAR(64),                     -- 供应商货号
    ref_price           NUMERIC(14,4),                   -- 参考单价：按采购单位、含税
    moq                 NUMERIC(14,3),                   -- 起订量，采购单位
    min_pack_qty        NUMERIC(14,3) NOT NULL DEFAULT 1,  -- 最小包装量，采购单位；建议采购量向上取整到它的倍数
    lead_days           INT,                             -- 采购周期（天）
    year                SMALLINT,
    season              VARCHAR(64),                     -- 字典 season
    default_source      VARCHAR(32)   NOT NULL DEFAULT 'PURCHASE',  -- BOM 新增行时带出：PURCHASE 我方采购 / FACTORY 工厂自备 / CUSTOMER_SUPPLIED 客供（代工保留，TRADE 关时拒收）
    default_loss_pct    NUMERIC(6,2),                    -- 默认损耗 %，BOM 新增行时带出；空 = 0
    reserve_pct         NUMERIC(6,2),                    -- 备料比 %；空 = 用物料类型字典项的数值
    status              SMALLINT      NOT NULL DEFAULT 1,
    remark              VARCHAR(500),
    legacy_attrs        JSONB,                           -- 领猫附加属性（面料功能、经缩纬缩、卷重……），只读显示
    create_by           BIGINT,
    create_time         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by           BIGINT,
    update_time         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_material_code ON bas_material (code);
-- 两个索引同时给外键用、给建档查重用（同分类同名、同供应商同货号，RFC 003 §7.2 只提示不拦）
CREATE INDEX idx_bas_material_category ON bas_material (category_id, name);
CREATE INDEX idx_bas_material_supplier ON bas_material (default_supplier_id, supplier_item_no);
COMMENT ON TABLE bas_material IS '物料';

-- 2. 物料 SKU：物料 × 颜色 × 规格。采购、库存、BOM 配料、算料都落在它上面
CREATE TABLE bas_material_sku (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    material_id       BIGINT        NOT NULL REFERENCES bas_material (id),
    code              VARCHAR(64)   NOT NULL,            -- 物料编码-颜色码[-规格码]，自动拼、不能改；导入的照用领猫的
    color_id          BIGINT        REFERENCES bas_color (id),  -- 无色物料为空
    spec              VARCHAR(64),                       -- 规格，自由填，如 60cm
    spec_code         VARCHAR(16),                       -- 规格码，进 SKU 码；默认取规格去掉空格和单位再转大写，新建时可改
    supplier_color_no VARCHAR(64),                       -- 供应商色号
    supplier_item_no  VARCHAR(64),                       -- SKU 级货号；空 = 用物料的
    price             NUMERIC(14,4),                     -- 单价，按采购单位、含税；空 = 用物料参考单价
    safety_stock      NUMERIC(14,3),                     -- 安全库存，库存单位
    status            SMALLINT      NOT NULL DEFAULT 1,
    remark            VARCHAR(255),
    create_by         BIGINT,
    create_time       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_mat_sku_code ON bas_material_sku (code);
-- 同一物料同色同规格只有一个。颜色、规格码可空，NULL 和 NULL 不算重复，所以换成 0 和空串；第一列 material_id 也给外键用
CREATE UNIQUE INDEX uk_bas_mat_sku_combo ON bas_material_sku (material_id, COALESCE(color_id, 0), COALESCE(spec_code, ''));
CREATE INDEX idx_bas_mat_sku_color ON bas_material_sku (color_id);
COMMENT ON TABLE bas_material_sku IS '物料 SKU';

-- 3. 仓库：物料仓、成品仓页面上手建；工厂仓由供应商同步（RFC 003 §6），料在工厂手上，领料就是移库
CREATE TABLE bas_warehouse (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL,                   -- 手填；工厂仓 = GC + 供应商编码里的数字（GYS0004 → GC0004）；建好不改
    name        VARCHAR(100) NOT NULL,                   -- 工厂仓 = 供应商简称 + 仓，随供应商改名
    type        VARCHAR(16)  NOT NULL,                   -- MATERIAL 物料仓 / PRODUCT 成品仓 / FACTORY 工厂仓；以后质量模块加待检仓、不良品仓
    factory_id  BIGINT       REFERENCES sup_supplier (id),  -- 工厂仓绑定的工厂；别的仓为空
    address     VARCHAR(255),
    keeper_id   BIGINT       REFERENCES sys_user (id),   -- 负责人
    phone       VARCHAR(32),
    status      SMALLINT     NOT NULL DEFAULT 1,
    remark      VARCHAR(500),
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 工厂仓有且只有工厂仓绑工厂：同步、删除供应商时按 factory_id 找仓，绑错了就找错仓。
    -- 只约束这一对，不约束 type 的取值：以后加仓库类型不用改它（数据库设计 §1.3）
    CONSTRAINT ck_bas_warehouse_factory CHECK ((type = 'FACTORY') = (factory_id IS NOT NULL))
);
CREATE UNIQUE INDEX uk_bas_warehouse_code ON bas_warehouse (code);
-- 一个工厂一个工厂仓：两个请求同时给同一家建仓，后一个被拦住（1000 刷新再试）；也给外键用
CREATE UNIQUE INDEX uk_bas_warehouse_factory ON bas_warehouse (factory_id) WHERE factory_id IS NOT NULL;
CREATE INDEX idx_bas_warehouse_keeper ON bas_warehouse (keeper_id);
COMMENT ON TABLE bas_warehouse IS '仓库';
