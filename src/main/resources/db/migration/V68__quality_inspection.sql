-- V68：质检（甲方 11-27 答 Q53，RFC 019 §6，PRD M09）。照原型"生产 › 质检"做：
-- 待检（已审核的采购入库、登记了的回货）→ 验货记一条 → 回货不合格的返修 → 返修完成回到待检、再验一次（第 2 次……）；
-- 产前样、中检对生产单记；要扣钱的同时登记一笔扣款（stl_deduction，月底进对账单）。
-- 流程甲方没给，不审核，验了就算；回货的质检状态由这张表推出来，不往回货单上加列（数据库设计 §17.3 原来想加 qc_status）

-- 1. 质检记录
CREATE TABLE qc_inspection (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    no               VARCHAR(32)   NOT NULL,                                -- QC + yyyyMMdd + 4 位流水
    company_id       BIGINT        NOT NULL REFERENCES bas_company (id),    -- 主体：跟着来源单
    kind             VARCHAR(16)   NOT NULL,                                -- INCOMING 来料检验 / PRE_PRODUCTION 产前样 / MIDDLE 中检 / FINAL 尾期验货
    inv_doc_id       BIGINT        REFERENCES inv_doc (id),                 -- 来料检验：哪张采购入库单
    prd_receipt_id   BIGINT        REFERENCES prd_receipt (id),             -- 尾期验货：哪张回货单
    prd_order_id     BIGINT        REFERENCES prd_order (id),               -- 产前样、中检的生产单；尾期验货是回货单的生产单
    attempt_no       INT           NOT NULL DEFAULT 1,                      -- 同一张来源单第几次验（回货返修后再验 +1）
    partner_id       BIGINT        NOT NULL REFERENCES sup_supplier (id),   -- 供应商或加工厂
    product_line     VARCHAR(16),                                           -- 生产单的品类线；来料检验空（物料出入库单不分品类线）
    target_desc      VARCHAR(255),                                          -- 验的是什么：物料名、款号款名（快照）
    lot_qty          NUMERIC(14,3),                                         -- 批量：入库的采购数量、回货件数、生产单件数
    unit             VARCHAR(64),                                           -- 批量的单位（字典 unit）
    inspect_date     DATE          NOT NULL,
    sample_qty       NUMERIC(14,3),                                         -- 抽检数（产前样可以不填）
    defect_qty       NUMERIC(14,3),                                         -- 不合格数
    accept_qty       NUMERIC(14,3),                                         -- 允收数 Ac（AQL 2.5、一般检验水平 II，按批量算）
    result           VARCHAR(16)   NOT NULL,                                -- PASS 合格 / CONCESSION 让步接收 / FAIL 不合格
    defect_types     VARCHAR(32)[] NOT NULL DEFAULT '{}',                   -- 问题类型（字典 qc_defect_type）
    note             VARCHAR(1000),                                         -- 问题；不是合格的必填
    rework_note      VARCHAR(500),                                          -- 尾期验货不合格安排返修：返修什么、多少件、哪天送回
    rework_by        BIGINT,
    rework_time      TIMESTAMPTZ,
    rework_done_by   BIGINT,                                                -- 返修完成（回到待检）
    rework_done_time TIMESTAMPTZ,
    deduction_id     BIGINT        REFERENCES stl_deduction (id),           -- 质量扣款：同时登记的那笔扣款
    version          INT           NOT NULL DEFAULT 0,
    create_by        BIGINT,                                                -- 检验人
    create_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by        BIGINT,
    update_time      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_qc_kind CHECK (kind IN ('INCOMING', 'PRE_PRODUCTION', 'MIDDLE', 'FINAL')),
    CONSTRAINT ck_qc_source CHECK (
        (kind = 'INCOMING' AND inv_doc_id IS NOT NULL AND prd_receipt_id IS NULL AND prd_order_id IS NULL)
        OR (kind = 'FINAL' AND prd_receipt_id IS NOT NULL AND prd_order_id IS NOT NULL AND inv_doc_id IS NULL)
        OR (kind IN ('PRE_PRODUCTION', 'MIDDLE') AND prd_order_id IS NOT NULL AND inv_doc_id IS NULL AND prd_receipt_id IS NULL)),
    CONSTRAINT ck_qc_result CHECK (result IN ('PASS', 'CONCESSION', 'FAIL')),
    CONSTRAINT ck_qc_qty CHECK ((sample_qty IS NULL OR sample_qty >= 0) AND (defect_qty IS NULL OR defect_qty >= 0)
        AND (sample_qty IS NULL OR defect_qty IS NULL OR defect_qty <= sample_qty)),
    CONSTRAINT ck_qc_attempt CHECK (attempt_no >= 1),
    -- 只有尾期验货不合格的能返修；返修完成要先安排返修
    CONSTRAINT ck_qc_rework CHECK ((rework_time IS NULL OR (kind = 'FINAL' AND result = 'FAIL'))
        AND (rework_done_time IS NULL OR rework_time IS NOT NULL))
);
CREATE UNIQUE INDEX uk_qc_inspection_no ON qc_inspection (no);
-- 来料检验一张入库单只验一次；回货、生产单同一种检验第几次不重（两个人同时验，后提交的报 1002）
CREATE UNIQUE INDEX uk_qc_incoming ON qc_inspection (inv_doc_id) WHERE kind = 'INCOMING';
CREATE UNIQUE INDEX uk_qc_receipt_attempt ON qc_inspection (prd_receipt_id, attempt_no) WHERE kind = 'FINAL';
CREATE UNIQUE INDEX uk_qc_order_attempt ON qc_inspection (prd_order_id, kind, attempt_no) WHERE kind IN ('PRE_PRODUCTION', 'MIDDLE');
CREATE UNIQUE INDEX uk_qc_deduction ON qc_inspection (deduction_id) WHERE deduction_id IS NOT NULL;
CREATE INDEX idx_qc_company ON qc_inspection (company_id);
CREATE INDEX idx_qc_inv_doc ON qc_inspection (inv_doc_id) WHERE inv_doc_id IS NOT NULL;
CREATE INDEX idx_qc_receipt ON qc_inspection (prd_receipt_id) WHERE prd_receipt_id IS NOT NULL;
CREATE INDEX idx_qc_prd_order ON qc_inspection (prd_order_id) WHERE prd_order_id IS NOT NULL;
CREATE INDEX idx_qc_partner ON qc_inspection (partner_id, inspect_date);
CREATE INDEX idx_qc_date ON qc_inspection (inspect_date DESC, id DESC);
COMMENT ON TABLE qc_inspection IS '质检记录：来料检验、产前样、中检、尾期验货（RFC 019 §6）';

-- 2. 扣款登记记"是谁登记的"：质检记录登记的扣款只能在质检记录里改、删（扣款登记页不给）
ALTER TABLE stl_deduction
    ADD COLUMN source_type VARCHAR(32),     -- QC_INSPECTION；手工登记的空
    ADD COLUMN source_id   BIGINT,          -- 质检记录 id；类型不固定，没有外键（qc_inspection.deduction_id 有外键指回来）
    ADD CONSTRAINT ck_stl_deduction_source CHECK ((source_type IS NULL) = (source_id IS NULL)
        AND (source_type IS NULL OR source_type IN ('QC_INSPECTION')));
CREATE INDEX idx_stl_deduction_source ON stl_deduction (source_type, source_id) WHERE source_type IS NOT NULL;

-- 3. 问题类型字典（管理员能加）
INSERT INTO sys_dict_type (code, name, remark)
SELECT 'qc_defect_type', '质检问题类型', '验货时勾的问题类型，供应商质量按它统计'
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE code = 'qc_defect_type' AND deleted = false);

INSERT INTO sys_dict_data (type_code, label, value, sort)
SELECT 'qc_defect_type', v.label, v.value, v.sort
FROM (VALUES ('色差', 'COLOR', 1), ('尺寸', 'SIZE', 2), ('做工', 'WORKMANSHIP', 3), ('污渍', 'STAIN', 4),
             ('破损', 'DAMAGE', 5), ('面料瑕疵', 'FABRIC', 6), ('辅料', 'TRIM', 7), ('包装', 'PACKING', 8),
             ('其他', 'OTHER', 9)) AS v(label, value, sort)
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data d WHERE d.type_code = 'qc_defect_type' AND d.value = v.value AND d.deleted = false);

-- 4. 菜单：生产 › 质检（原型的位置），手机报工后面
WITH page AS (
    INSERT INTO sys_menu (parent_id, name, type, path, component, perms, icon, sort, visible, admin_only)
    SELECT d.id, '质检', 2, '/qc', 'quality/inspection/index', 'quality:inspection:list', 'CircleCheck', 50, true, false
    FROM sys_menu d
    WHERE d.parent_id = 0 AND d.path = '/make' AND d.deleted = false
      AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'quality:inspection:list' AND deleted = false)
    RETURNING id
)
INSERT INTO sys_menu (parent_id, name, type, perms, sort, admin_only)
SELECT page.id, '验货、返修、改、删', 3, 'quality:inspection:edit', 1, false
FROM page;

-- 授权：跟单、仓管、生产经理、理单、管理层验货；财务、采购只看
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.id
FROM (VALUES ('quality:inspection:list', ARRAY['merchandiser', 'warehouse', 'prod_manager', 'order_clerk', 'manager', 'finance', 'buyer']),
             ('quality:inspection:edit', ARRAY['merchandiser', 'warehouse', 'prod_manager', 'order_clerk', 'manager'])) AS g(perm, roles)
JOIN sys_menu m ON m.perms = g.perm AND m.deleted = false
JOIN sys_role r ON r.code = ANY(g.roles) AND r.deleted = false
ON CONFLICT DO NOTHING;

-- 上级（页面、"生产"目录；仓管、财务原来没有"生产"目录的也补上）
WITH RECURSIVE up (role_id, menu_id) AS (
    SELECT rm.role_id, m.parent_id
    FROM sys_role_menu rm
    JOIN sys_menu m ON m.id = rm.menu_id AND m.perms LIKE 'quality:%' AND m.deleted = false
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
