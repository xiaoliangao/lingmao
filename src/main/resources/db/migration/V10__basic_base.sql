-- V10：基础资料的第一个文件（RFC 003 §11 第 1 步，数据库设计 §5.1、§5.2、§5.4–§5.8）：
-- 主体公司、客户、品类、颜色、尺码组、物料分类。物料、仓库指向供应商，放在 V12 供应商之后的 V13；
-- 供应商的擅长品类又指向这里的品类，所以 basic 拆成两个文件。
--
-- 第 2 批的新表（V10、V12–V15）都照这几条写（数据库设计 §1、架构 §6）：
--   - 不用逻辑删除，没有 deleted：没被引用的直接 DELETE，被引用的由外键拦住，报 1005"已被××引用，不能删除，只能停用"；
--   - 外键都加、不级联（REFERENCES 不写动作就是 NO ACTION），每个外键列都是某个索引的第一列：删被引用的一方时，
--     数据库要按这一列查有没有引用它的行，没有索引就是全表扫；
--   - 每张表写 COMMENT ON TABLE：1005 的提示拿它当中文名，所以写成放进"已被××引用"读得通的名词；
--   - 主数据 = id + 业务列 + 四个审计列（create_by、update_by 是审计人，不加外键）；关联表、尺码这种跟着上级整批改的行没有审计列；
--   - 数量、单价、金额用 NUMERIC，启用停用 SMALLINT（1 启用 0 停用），多状态、枚举存英文名 VARCHAR，字典列存字典的 value；
--   - 唯一索引 uk_<表>_<列>、普通索引 idx_<表>_<列>（表名长的用数据库设计里的缩写）。
-- SchemaConventionsIT 查表注释、外键动作、外键索引这几条，以后每批的新表自动受它检查。

-- 1. 主体公司：甲方名下做生意的主体，每张单据记 company_id（PRD §6.1.1、§5.7）。
--    预置为空：主体是甲方的名字，不进公开仓库，甲方在 资料 › 基础 里建
CREATE TABLE bas_company (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(6)   NOT NULL,                   -- 2-6 位字母数字，转大写；建好不改（RFC 003 §2）
    name        VARCHAR(100) NOT NULL,                   -- 全称，打印抬头用
    short_name  VARCHAR(50)  NOT NULL,                   -- 简称，列表和单据上显示
    credit_code VARCHAR(18),                             -- 统一社会信用代码
    address     VARCHAR(255),
    phone       VARCHAR(32),
    status      SMALLINT     NOT NULL DEFAULT 1,         -- 1 启用 0 停用；至少留一个启用的（Service 锁住启用的行再数）
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_company_code ON bas_company (code);
COMMENT ON TABLE bas_company IS '主体公司';

-- 2. 客户（贸易代工，PRD §6.1.8）：TRADE 关着，一期是空表，没有实体、接口、页面。
--    先建出来，款式、需求订单、库存物权上的 customer_id 才能加外键（架构 §14.4）
CREATE TABLE bas_customer (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code          VARCHAR(32)  NOT NULL,                 -- KH + 4 位
    name          VARCHAR(100) NOT NULL,
    short_name    VARCHAR(50)  NOT NULL,
    contact       VARCHAR(50),
    phone         VARCHAR(32),
    address       VARCHAR(255),
    settle_method VARCHAR(64),                           -- 结算方式，字典 settle_method
    credit_days   INT,                                   -- 账期（天）
    status        SMALLINT     NOT NULL DEFAULT 1,
    remark        VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by     BIGINT,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_customer_code ON bas_customer (code);
COMMENT ON TABLE bas_customer IS '客户';

-- 3. 款式品类：三级树，如 女装 > 外套 > 风衣（PRD §6.1.2）。不进款号，没有编码。
--    树形表的上级可空、自引用外键，顶层为空（sys_menu 的 parent_id = 0 是 V4 的写法，0 加不了外键）
CREATE TABLE bas_category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id   BIGINT       REFERENCES bas_category (id),  -- 上级；建好不改（不挪节点）
    name        VARCHAR(50)  NOT NULL,
    level       SMALLINT     NOT NULL,                   -- 1-3，由上级算
    sort        INT          NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 同一上级下不重名。顶层的 parent_id 是 NULL，唯一索引里 NULL 和 NULL 不算重复，所以按 0 算
CREATE UNIQUE INDEX uk_bas_category_name ON bas_category (COALESCE(parent_id, 0), name);
CREATE INDEX idx_bas_category_parent ON bas_category (parent_id);
COMMENT ON TABLE bas_category IS '品类';

-- 4. 颜色：款式和物料共用一套，颜色码进 SKU 码（PRD §6.1.3）
CREATE TABLE bas_color (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(3)   NOT NULL,                   -- 2-3 位字母数字，转大写，如 KQ；建好不改
    name        VARCHAR(32)  NOT NULL,                   -- 如 卡其
    family      VARCHAR(64),                             -- 色系，字典 color_family
    hex         VARCHAR(7),                              -- 色块 #RRGGBB，只用于显示
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_color_code ON bas_color (code);
CREATE UNIQUE INDEX uk_bas_color_name ON bas_color (name);
COMMENT ON TABLE bas_color IS '颜色';

-- 5. 尺码组：一组有顺序的尺码，如 女装字母码（PRD §6.1.3）。
--    建好以后只能在末尾追加尺码，不能删、改、调顺序，基码不能换（RFC 003 §5.3）；名称能改（别的表存的是 size_code）
CREATE TABLE bas_size_group (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    base_size   VARCHAR(16),                             -- 基码，如 M：BOM 统一用量、尺寸指示、核价都按它
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_size_group_name ON bas_size_group (name);
COMMENT ON TABLE bas_size_group IS '尺码组';

-- 尺码组的尺码，跟着尺码组建、删，没有审计列。别的表存 size_code 字符串，不存这里的 id：
-- 尺码不改名，字符串直接进 SKU 码，单据上看得懂
CREATE TABLE bas_size_group_item (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    size_group_id BIGINT       NOT NULL REFERENCES bas_size_group (id),
    seq           INT          NOT NULL,                 -- 顺序，从 1 起
    size_code     VARCHAR(16)  NOT NULL                  -- 1-16 位，转大写，如 M、165/88A
);
CREATE UNIQUE INDEX uk_bas_size_item ON bas_size_group_item (size_group_id, size_code);
CREATE UNIQUE INDEX uk_bas_size_item_seq ON bas_size_group_item (size_group_id, seq);
COMMENT ON TABLE bas_size_group_item IS '尺码组的尺码';

-- 6. 物料分类：两级树。一级只有名称（面料、辅料、包材……）；二级必须有 2 位字母编码，就是物料编码的前缀（PRD §6.1.4）
CREATE TABLE bas_material_category (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_id   BIGINT       REFERENCES bas_material_category (id),  -- 一级为空
    code        VARCHAR(2),                              -- 2 位大写字母，二级必填（Service 校验）；建好不改
    name        VARCHAR(50)  NOT NULL,
    level       SMALLINT     NOT NULL,                   -- 1 或 2
    sort        INT          NOT NULL DEFAULT 0,
    status      SMALLINT     NOT NULL DEFAULT 1,
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_bas_mat_cat_code ON bas_material_category (code) WHERE code IS NOT NULL;
CREATE UNIQUE INDEX uk_bas_mat_cat_name ON bas_material_category (COALESCE(parent_id, 0), name);
CREATE INDEX idx_bas_mat_cat_parent ON bas_material_category (parent_id);
COMMENT ON TABLE bas_material_category IS '物料分类';
