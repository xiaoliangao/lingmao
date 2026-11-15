-- V15：价格档案（RFC 006 §2，数据库设计 §7.1，架构 §5.5）。指向供应商、物料、物料 SKU、款式，排在它们之后。
-- 只插不改：调价插新的，录错作废；只有作废的四列能更新（Mapper 里只写作废那一条 UPDATE）。
-- 只有创建人、创建时间两个审计列（数据库设计 §1.2 只插表）。
-- 有价格记录的供应商、物料、SKU、款式都删不掉，只能停用（作废的记录也算）。写法见 V10 开头。

CREATE TABLE prc_price (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- 页面上的序号就是它
    type             VARCHAR(16)   NOT NULL,             -- MATERIAL 物料价 / PROCESS 加工价
    supplier_id      BIGINT        NOT NULL REFERENCES sup_supplier (id),     -- 供应商，或加工价的工厂
    material_id      BIGINT        REFERENCES bas_material (id),              -- 物料价必填
    material_sku_id  BIGINT        REFERENCES bas_material_sku (id),          -- 空 = 这个物料所有颜色同价（物料层的价）
    style_id         BIGINT        REFERENCES sty_style (id),                 -- 加工价必填
    process_mode     VARCHAR(64),                        -- 加工价必填，字典 process_mode；FULL 包工包料
    price            NUMERIC(14,4) NOT NULL,             -- 单价，> 0
    unit             VARCHAR(64)   NOT NULL,             -- 记录时的单位：物料价 = 物料当时的采购单位，加工价 = PIECE 件；取现价只认物料现在的采购单位
    tax_pct          NUMERIC(6,2)  NOT NULL,             -- 税率 %，默认带供应商的
    tax_included     BOOLEAN       NOT NULL DEFAULT true,
    freight_included BOOLEAN       NOT NULL DEFAULT true,
    effective_date   DATE          NOT NULL,             -- 生效日期：可以补录以前的，也可以填以后的
    reason           VARCHAR(255),                       -- 调价原因；同一层级已有现价时必填
    remark           VARCHAR(255),                       -- 备注；以后领猫多供应商表导入时记供应商货号、色号
    source_type      VARCHAR(32),                        -- 来源单据类型（BizType 的名字）；手工录的为空
    source_id        BIGINT,                             -- 来源单据 id：类型 + id 指向各种单据，加不了外键（数据库设计 §1.4）
    source_no        VARCHAR(32),                        -- 来源单号，冗余一份方便列表显示
    voided           BOOLEAN       NOT NULL DEFAULT false,  -- 作废的不参与取价
    void_reason      VARCHAR(255),
    void_by          BIGINT,
    void_time        TIMESTAMPTZ,
    create_by        BIGINT,                             -- 记录人
    create_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 物料价、加工价各自的必填列由数据库保证：Java 漏了校验也写不进一条两边都不像的价（RFC 006 §2）
    CONSTRAINT ck_prc_price_type CHECK (
        (type = 'MATERIAL' AND material_id IS NOT NULL AND style_id IS NULL AND process_mode IS NULL)
        OR (type = 'PROCESS' AND style_id IS NOT NULL AND process_mode IS NOT NULL
            AND material_id IS NULL AND material_sku_id IS NULL))
);
-- 取现价（架构 §5.5）：effective_date <= ? AND NOT voided ORDER BY effective_date DESC, id DESC LIMIT 1
CREATE INDEX idx_prc_price_mat ON prc_price (type, supplier_id, material_id, effective_date);
CREATE INDEX idx_prc_price_proc ON prc_price (type, supplier_id, style_id, process_mode, effective_date);
-- 外键和详情页"价格记录"页签：上面两个以 type 打头，删供应商、按供应商查都用不上，供应商另建一个
CREATE INDEX idx_prc_price_supplier ON prc_price (supplier_id);
CREATE INDEX idx_prc_price_material ON prc_price (material_id);
CREATE INDEX idx_prc_price_sku ON prc_price (material_sku_id);
CREATE INDEX idx_prc_price_style ON prc_price (style_id);
CREATE INDEX idx_prc_price_source ON prc_price (source_type, source_id);
COMMENT ON TABLE prc_price IS '价格档案';
