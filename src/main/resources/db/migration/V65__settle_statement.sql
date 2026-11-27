-- V65：供应商对账（甲方 11-27 答 Q68，RFC 019 §5，数据库设计 §19.1、§19.5）。
-- 对账单 = 主体 × 供应商或加工厂 × 期间：拉进这段时间里还没对过的采购入库、采购退货、回货、扣款，双方确认。
-- 对方的 Excel 对账单比对结果存在单头 compare_result（快照，每处差异的说明也在里面），原件是对账单的附件。
-- 照片、PDF 识别（§19.2 的 stl_ocr_*）等第三方账号，这里不建。

-- 1. 对账单
CREATE TABLE stl_statement (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no             VARCHAR(32)   NOT NULL,                              -- DZ + yyyyMMdd + 4 位流水
    company_id     BIGINT        NOT NULL REFERENCES bas_company (id),  -- 主体：只拉这个主体的单据
    partner_id     BIGINT        NOT NULL REFERENCES sup_supplier (id), -- 供应商或加工厂
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,
    statement_date DATE          NOT NULL,                              -- 生成那天
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',              -- DRAFT / SENT 已发送 / CONFIRMED 已确认 / PAID 已付款（付款计划做了才用）/ VOIDED；动作改
    total_amount   NUMERIC(14,2) NOT NULL DEFAULT 0,                    -- 货款合计（退货是负的），明细变了重算
    deduct_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,                    -- 扣款合计
    payable_amount NUMERIC(14,2) NOT NULL DEFAULT 0,                    -- 实付 = 货款 − 扣款
    their_amount   NUMERIC(14,2),                                       -- 对方对账单上的合计（上传比对后有）
    compare_result JSONB,                                               -- 和对方对账单的比对结果（快照）；明细变了清空
    sent_by        BIGINT,
    sent_time      TIMESTAMPTZ,
    audit_by       BIGINT,                                              -- 确认人（单头公共列，DocEntity）
    audit_time     TIMESTAMPTZ,                                         -- 确认时间
    confirm_note   VARCHAR(500),
    void_reason    VARCHAR(255),
    void_by        BIGINT,
    void_time      TIMESTAMPTZ,
    version        INT           NOT NULL DEFAULT 0,
    remark         VARCHAR(500),
    create_by      BIGINT,
    create_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by      BIGINT,
    update_time    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_stl_statement_period CHECK (period_end >= period_start),
    CONSTRAINT ck_stl_statement_status CHECK (status IN ('DRAFT', 'SENT', 'CONFIRMED', 'PAID', 'VOIDED'))
);
CREATE UNIQUE INDEX uk_stl_statement_no ON stl_statement (no);
CREATE INDEX idx_stl_statement_partner ON stl_statement (partner_id, period_start);
CREATE INDEX idx_stl_statement_company ON stl_statement (company_id);
CREATE INDEX idx_stl_statement_status ON stl_statement (status, create_time DESC);
COMMENT ON TABLE stl_statement IS '供应商对账单';

-- 2. 扣款登记：平时登记，月底按日期拉进对账单。进没进对账单查 stl_statement_item（不在这里另记状态）
CREATE TABLE stl_deduction (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id  BIGINT        NOT NULL REFERENCES bas_company (id),
    partner_id  BIGINT        NOT NULL REFERENCES sup_supplier (id),  -- 扣谁的
    biz_date    DATE          NOT NULL,                               -- 按它算进哪个期间
    amount      NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reason      VARCHAR(500)  NOT NULL,
    ref_no      VARCHAR(64),                                          -- 相关单号（选填，写字）
    version     INT           NOT NULL DEFAULT 0,
    create_by   BIGINT,
    create_time TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_stl_deduction_partner ON stl_deduction (partner_id, biz_date);
CREATE INDEX idx_stl_deduction_company ON stl_deduction (company_id);
COMMENT ON TABLE stl_deduction IS '扣款登记';

-- 3. 对账单明细：一行一条入库明细、一条退货明细、一张回货单、一条扣款
CREATE TABLE stl_statement_item (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    statement_id   BIGINT        NOT NULL REFERENCES stl_statement (id),
    seq            INT           NOT NULL,
    source_type    VARCHAR(32)   NOT NULL,          -- PURCHASE_IN / PURCHASE_RETURN / PRODUCTION_RECEIPT / DEDUCTION
    source_id      BIGINT        NOT NULL,          -- 入库单、退货单（inv_doc）、回货单、扣款的 id；类型不固定，没有外键
    source_item_id BIGINT,                          -- 入库、退货的明细 id；回货、扣款是空
    source_no      VARCHAR(64),                     -- 单号快照；扣款是登记时写的相关单号
    order_no       VARCHAR(32),                     -- 采购单号、生产单号快照（比对时认）
    delivery_no    VARCHAR(64),                     -- 供应商送货单号快照（比对时认）
    biz_date       DATE          NOT NULL,
    description    VARCHAR(255),                    -- 物料名颜色规格、款号款名次品数、扣款原因
    unit           VARCHAR(16),                     -- 采购单位（字典 unit）；回货是件，空着
    qty            NUMERIC(14,3),                   -- 退货是负的；扣款是空
    price          NUMERIC(14,4),                   -- 采购单价、生产单单价；没有单价是空
    amount         NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 货款，退货是负的，扣款是 0
    deduct_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,  -- 扣款：回货次品 × 单价、扣款登记的金额
    active         BOOLEAN       NOT NULL DEFAULT true, -- 对账单作废时置 false，单据回到"待对账"
    CONSTRAINT ck_stl_item_source CHECK (source_type IN ('PURCHASE_IN', 'PURCHASE_RETURN', 'PRODUCTION_RECEIPT', 'DEDUCTION')),
    CONSTRAINT ck_stl_item_deduct CHECK (deduct_amount >= 0)
);
-- 一条源单明细只能进一张没作废的对账单（两个人同时生成也不会重复）
CREATE UNIQUE INDEX uk_stl_item_source ON stl_statement_item (source_type, COALESCE(source_item_id, source_id)) WHERE active;
CREATE INDEX idx_stl_item_statement ON stl_statement_item (statement_id, seq);
-- 引用查询（入库单反审核、回货单作废前查）：按源单 id
CREATE INDEX idx_stl_item_source_doc ON stl_statement_item (source_type, source_id) WHERE active;
COMMENT ON TABLE stl_statement_item IS '供应商对账单明细';

-- 4. 菜单：新目录"对账结算"（PRD §2.5 M11、原型的叫法；成品后面、报表前面），两个页面
WITH dir AS (
    INSERT INTO sys_menu (parent_id, name, type, path, icon, sort)
    SELECT 0, '对账结算', 1, '/settle', 'Wallet', 55
    WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE parent_id = 0 AND path = '/settle' AND deleted = false)
    RETURNING id
), pages AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
    SELECT dir.id, p.name, 2, p.path, p.component, p.perms, p.icon, p.sort, true, false
    FROM dir
    CROSS JOIN (VALUES ('供应商对账', '/statements', 'settle/statement/index', 'settle:statement:list', 'Tickets', 10),
                       ('扣款登记',   '/deductions', 'settle/deduction/index', 'settle:deduction:list', 'Coin',    20))
         AS p(name, path, component, perms, icon, sort)
    RETURNING id, name
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT pages.id, b.name, 3, b.perms, b.sort, false
FROM pages
JOIN (VALUES ('供应商对账', '生成、改、发送', 'settle:statement:edit',    1),
             ('供应商对账', '确认',           'settle:statement:confirm', 2),
             ('扣款登记',   '登记、改、删',   'settle:deduction:edit',    1)) AS b(page, name, perms, sort)
  ON b.page = pages.name;

-- 授权：理单、财务、总经理对账；采购、跟单、生产经理只看
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('settle:statement:list',    ARRAY['order_clerk', 'finance', 'manager', 'buyer', 'merchandiser', 'prod_manager']),
             ('settle:statement:edit',    ARRAY['order_clerk', 'finance', 'manager']),
             ('settle:statement:confirm', ARRAY['order_clerk', 'finance', 'manager']),
             ('settle:deduction:list',    ARRAY['order_clerk', 'finance', 'manager', 'buyer', 'merchandiser', 'prod_manager']),
             ('settle:deduction:edit',    ARRAY['order_clerk', 'finance', 'manager', 'merchandiser'])) AS g(perm, roles)
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false
JOIN sys_role r ON r.code = ANY(g.roles) AND r.deleted = false
ON CONFLICT DO NOTHING;

-- 上级（页面、目录）
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_menu m ON m.id = rm.menu_id AND m.perms LIKE 'settle:%' AND m.deleted = false
    WHERE m.parent_id <> 0
    UNION
    SELECT up.role_id, m.parent_id
    FROM up
    JOIN sys_menu m ON m.id = up.menu_id
    WHERE m.parent_id <> 0
)
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT role_id, menu_id FROM up
ON CONFLICT DO NOTHING;
