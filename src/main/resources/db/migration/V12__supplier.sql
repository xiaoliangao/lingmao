-- V12：供应商（RFC 004 §3，数据库设计 §6.1–§6.5）。风险快照、风险待查（待排期）、多收款账户（M11）这次不建。
-- 擅长品类指向 V10 的 bas_category；物料的默认供应商、工厂仓指向这里的 sup_supplier，所以物料和仓库在 V13。
-- 写法见 V10 开头。

-- 1. 供应商：面料商、辅料商、成衣厂、加工厂统一建档（PRD §6.8.1）。
--    ★ 是关键字段：合作中、停用、黑名单的改了要走 sup_supplier_change 审批（RFC 004 §4）；
--    "动作改"的列（状态、准入、拉黑）普通保存写不到（实体上 NEVER）
CREATE TABLE sup_supplier (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code              VARCHAR(32)  NOT NULL,             -- 新建 GYS + 4 位；导入的照用领猫编码；建好不改
    name              VARCHAR(100) NOT NULL,             -- ★ 全称
    short_name        VARCHAR(50)  NOT NULL,             -- 简称；工厂仓的名字 = 简称 + 仓
    credit_code       VARCHAR(18),                       -- ★ 统一社会信用代码，按 GB 32100 校验；申请准入时必填
    level             VARCHAR(1),                        -- 等级 A / B / C / D
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING_ADMIT',  -- PENDING_ADMIT 待准入 / ADMITTING 准入审批中 / ACTIVE 合作中 / DISABLED 停用 / BLACKLISTED 黑名单；动作改
    contact           VARCHAR(50),                       -- 联系人，申请准入时必填
    phone             VARCHAR(32),
    wechat            VARCHAR(64),
    address           VARCHAR(255),
    settle_method     VARCHAR(64),                       -- ★ 结算方式，字典 settle_method
    credit_days       INT,                               -- ★ 账期（天）
    invoice_type      VARCHAR(64),                       -- ★ 发票类型，字典 invoice_type
    tax_pct           NUMERIC(6,2),                      -- ★ 税率 %，13 表示 13%；采购单、生产单、价格档案默认带它
    bank_name         VARCHAR(100),                      -- ★ 开户行，申请准入时必填
    bank_account      VARCHAR(40),                       -- ★ 账号，存文本；接口返回时遮成前 4 后 4（RFC 004 §5）
    bank_account_name VARCHAR(100),                      -- ★ 收款户名：个人户、关联公司收款时和全称不一样
    buyer_id          BIGINT       REFERENCES sys_user (id),  -- 负责采购员
    admit_apply_by    BIGINT,                            -- 准入申请人；不能自己批（和下面几个"谁做的"一样是审计人，不加外键）
    admit_apply_time  TIMESTAMPTZ,
    admit_by          BIGINT,                            -- 准入审批人；导入成合作中的记导入人
    admit_time        TIMESTAMPTZ,                       -- 准入时间；空 = 从没准入过，解除黑名单时回待准入（RFC 004 §2）
    black_reason      VARCHAR(500),
    black_by          BIGINT,
    black_time        TIMESTAMPTZ,
    remark            VARCHAR(500),
    legacy_attrs      JSONB,                             -- 领猫附加属性 {"领猫列名": "值"}，只读显示（数据库设计 §1.8）
    create_by         BIGINT,
    create_time       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by         BIGINT,
    update_time       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_sup_supplier_code ON sup_supplier (code);
-- 信用代码选填（领猫导出的多数没有），有值的全表唯一；重复时 Service 先查一次说出和哪家重复，这里兜底
CREATE UNIQUE INDEX uk_sup_supplier_credit ON sup_supplier (credit_code) WHERE credit_code IS NOT NULL;
CREATE INDEX idx_sup_supplier_status ON sup_supplier (status);
CREATE INDEX idx_sup_supplier_buyer ON sup_supplier (buyer_id);
COMMENT ON TABLE sup_supplier IS '供应商';

-- 2. 供应商分类，可多选（字典 supplier_category 的 value）。关联表，保存时整批删了重插
CREATE TABLE sup_supplier_category (
    supplier_id BIGINT      NOT NULL REFERENCES sup_supplier (id),
    category    VARCHAR(64) NOT NULL,                    -- 含 GARMENT 成衣厂、PROCESSOR 加工厂的自动建工厂仓
    PRIMARY KEY (supplier_id, category)
);
CREATE INDEX idx_sup_supplier_category ON sup_supplier_category (category);
COMMENT ON TABLE sup_supplier_category IS '供应商分类';

-- 3. 擅长品类：排单时参考。关联表，整批删了重插
CREATE TABLE sup_supplier_good_at (
    supplier_id BIGINT NOT NULL REFERENCES sup_supplier (id),
    category_id BIGINT NOT NULL REFERENCES bas_category (id),
    PRIMARY KEY (supplier_id, category_id)
);
CREATE INDEX idx_sup_good_at_category ON sup_supplier_good_at (category_id);
COMMENT ON TABLE sup_supplier_good_at IS '供应商擅长品类';

-- 4. 资质证照：营业执照、验厂报告……带有效期。文件先走上传接口，这里存不带签名的 /files/...，返回时签名
CREATE TABLE sup_supplier_cert (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id BIGINT       NOT NULL REFERENCES sup_supplier (id),
    cert_type   VARCHAR(64)  NOT NULL,                   -- 字典 cert_type；LICENSE 营业执照申请准入时必须有
    file_url    VARCHAR(255) NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    valid_until DATE,                                    -- 有效期至；空 = 长期
    remark      VARCHAR(255),
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_sup_cert_supplier ON sup_supplier_cert (supplier_id);
CREATE INDEX idx_sup_cert_until ON sup_supplier_cert (valid_until);      -- 列表筛"30 天内到期"
COMMENT ON TABLE sup_supplier_cert IS '供应商资质';

-- 5. 关键字段的待批改动（架构 §4.6、RFC 004 §4）：一家同时只有一份待批
CREATE TABLE sup_supplier_change (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_id   BIGINT       NOT NULL REFERENCES sup_supplier (id),
    changes       JSONB        NOT NULL,                 -- [{field, label, before, after}]，存全号（批准时写回），返回时账号遮掉
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',  -- PENDING 待批 / APPROVED 已批 / REJECTED 驳回 / WITHDRAWN 撤回
    apply_by      BIGINT       NOT NULL,                 -- 申请人；审批人不能是他
    apply_time    TIMESTAMPTZ  NOT NULL,
    audit_by      BIGINT,
    audit_time    TIMESTAMPTZ,
    reject_reason VARCHAR(500),
    create_by     BIGINT,
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by     BIGINT,
    update_time   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 两个人同时给同一家提改动，后一个被它拦住（Service 先查一次给出好懂的提示）
CREATE UNIQUE INDEX uk_sup_change_pending ON sup_supplier_change (supplier_id) WHERE status = 'PENDING';
CREATE INDEX idx_sup_change_supplier ON sup_supplier_change (supplier_id);
COMMENT ON TABLE sup_supplier_change IS '供应商改动审批';
