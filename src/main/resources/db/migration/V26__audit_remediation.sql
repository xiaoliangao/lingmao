-- V26：两轮审查的一期补强（RFC 010）。只增加列、表和约束，不改 V1–V25，不重写历史业务数据。
-- 库存数量修订戳用于盘点测量期间的收发检查；金额与资料包快照从新动作开始留存。
-- 历史没有保存的估值和版次展示信息继续为空，不从当前主数据或源单倒填成历史事实。

-- 1. 盘点：账面数量与修订戳必须在同一库存键锁内取得；同键每次产生库存流水的过账修订戳 +1。
--    数量先加后减回到原值也会使实盘失效。提交后仍可收发，审核只过已确认的差额。
ALTER TABLE inv_stock
    ADD COLUMN movement_revision BIGINT NOT NULL DEFAULT 0
        CONSTRAINT ck_inv_stock_movement_revision CHECK (movement_revision >= 0);

ALTER TABLE inv_count_item
    ADD COLUMN book_revision BIGINT
        CONSTRAINT ck_inv_count_item_book_revision CHECK (book_revision >= 0);

COMMENT ON COLUMN inv_stock.movement_revision IS '同一完整库存键的数量变动修订戳；过账时递增，拍账与提交校验使用';
COMMENT ON COLUMN inv_count_item.book_revision IS '拍账时同键 movement_revision；历史为空，须重新取账面并重数后才能提交';

-- 2. 节点整表计划：独立乐观锁，避免报工的单据版本变化影响计划编辑。
ALTER TABLE prd_order
    ADD COLUMN plan_revision INT NOT NULL DEFAULT 0
        CONSTRAINT ck_prd_order_plan_revision CHECK (plan_revision >= 0);

COMMENT ON COLUMN prd_order.plan_revision IS '节点计划版本；整表保存比较并递增，报工不修改';

-- 3. 新增并记数/过账的动作身份。占位与业务写入、结果回填在同一事务，失败不留占位。
--    不按业务内容自动去重：真实第二批相同数量必须使用新的 client_action_id。
CREATE TABLE biz_action_request (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES sys_user (id),
    operation        VARCHAR(64) NOT NULL CHECK (btrim(operation) <> ''),
    client_action_id UUID        NOT NULL,
    request_hash     CHAR(64)    NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    result           JSONB,
    create_time      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_biz_action_result CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);
CREATE UNIQUE INDEX uk_biz_action_request ON biz_action_request (user_id, operation, client_action_id);
COMMENT ON TABLE biz_action_request IS '新增业务动作重试记录';
COMMENT ON COLUMN biz_action_request.user_id IS '发起动作的用户；重试仍需通过当前接口和对象授权';
COMMENT ON COLUMN biz_action_request.operation IS '服务端固定的操作类型，区分回货、采购来源建单、生产来源建单';
COMMENT ON COLUMN biz_action_request.client_action_id IS '客户端为一次用户动作生成的 UUID，原动作重试复用';
COMMENT ON COLUMN biz_action_request.request_hash IS '包含目标对象及规范化请求体的 SHA-256；同键异体拒绝';
COMMENT ON COLUMN biz_action_request.result IS '成功动作的最小返回结构；NULL 只存在于未提交事务内';
COMMENT ON COLUMN biz_action_request.create_time IS '首次成功动作的事务时间';

-- 4. 库存流水的不可变金额依据。它记录源单当次凭证价，不实现移动加权核算。
--    旧流水、未估值期初和没有凭证价格的数量动作是 UNKNOWN；NULL 不等于零。
ALTER TABLE inv_stock_log
    ADD COLUMN unit_price NUMERIC(14,4),
    ADD COLUMN amount NUMERIC(14,2),
    ADD COLUMN stock_unit VARCHAR(64),
    ADD COLUMN currency VARCHAR(64),
    ADD COLUMN valuation_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN reversal_of_log_id BIGINT REFERENCES inv_stock_log (id),
    ADD CONSTRAINT ck_inv_log_unit_price CHECK (unit_price >= 0),
    ADD CONSTRAINT ck_inv_log_valuation CHECK (
        (valuation_status = 'UNKNOWN' AND unit_price IS NULL AND amount IS NULL)
        OR (valuation_status = 'RECORDED' AND unit_price IS NOT NULL AND amount IS NOT NULL
            AND stock_unit IS NOT NULL AND currency IS NOT NULL)
    ),
    ADD CONSTRAINT ck_inv_log_reversal CHECK (reversal_of_log_id <> id);

-- 一次正向过账事件最多被反审一次；重审产生新的事件身份。此索引同时覆盖自引用外键。
CREATE UNIQUE INDEX uk_inv_log_reversal ON inv_stock_log (reversal_of_log_id);
COMMENT ON COLUMN inv_stock_log.unit_price IS '当次凭证单价快照，元/库存单位；未知为 NULL，不从当前源单追算';
COMMENT ON COLUMN inv_stock_log.amount IS '当次凭证金额快照，随库存数量方向带正负号；反审与原事件相反';
COMMENT ON COLUMN inv_stock_log.stock_unit IS '当次库存单位字典值快照';
COMMENT ON COLUMN inv_stock_log.currency IS '凭证币种字典值；当前物料业务为 CNY，未知历史为 NULL';
COMMENT ON COLUMN inv_stock_log.valuation_status IS 'RECORDED 有当次凭证价 / UNKNOWN 未留存或尚未估值；不是财务结算状态';
COMMENT ON COLUMN inv_stock_log.reversal_of_log_id IS '反审所冲销的原库存流水；不通过正负数量猜历史业务身份';

-- 5. 新版资料包生效时固定版次展示上下文；草稿仍使用当前启用色码。
--    JSON 包含 schemaVersion、owner 显示字段、sizeGroupId/baseSize/sizes、colors(id/code/name/hex)。
--    旧生效版保持 NULL，渲染时明确 LEGACY 来源，不声称还原未保存的生效时展示值。
ALTER TABLE tp_tech_pack
    ADD COLUMN owner_snapshot JSONB,
    ADD CONSTRAINT ck_tp_pack_owner_snapshot CHECK (
        owner_snapshot IS NULL OR jsonb_typeof(owner_snapshot) = 'object'
    );

COMMENT ON COLUMN tp_tech_pack.owner_snapshot IS '生效时冻结的色码及主人展示上下文（schemaVersion=1）；历史 NULL 为 LEGACY，不伪造历史快照';
