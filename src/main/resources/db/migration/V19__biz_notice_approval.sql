-- V19：单据框架剩下的三张表（数据库设计 §4.3–§4.5，架构 §4.4、§4.8）：上游变更提醒、多级审批规则、每张单的审批流。
-- 设置 › 审批的菜单和按钮权限码（biz:approval-rule:list、:edit，管理后台、只给超级管理员）V17 已经插了，这里不再插。
-- 默认规则（PRD §5.1、Q10：采购单超过 5 万、生产单超过 10 万，管理层审完再给总经理审一级）不预置：
-- 预置角色模板里没有"总经理"，第 1 级（有审核权限的）就是管理层；规则的审批人设成管理层，等于同一批人连着审两级，
-- 一次就过了（架构 §4.8"一个人连着几级一起过"），规则形同虚设。甲方确认 Q10、建好总经理的角色或账号后，在设置 › 审批里加。
-- 新业务表，不用逻辑删除（架构 §6）。

-- 1. 上游变更提醒：需求订单的变更单审核后，给每张受影响的下游单据（生产单、采购单、算料单）插一条（PRD §6.5.6）。
--    用"类型 + id"指向收到提醒的单据和引起它的单据，没法加外键：只有 biz 的 DocNoticeService 写它（数据库设计 §1.4）
CREATE TABLE biz_doc_notice (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_type      VARCHAR(32)  NOT NULL,
    doc_id        BIGINT       NOT NULL,
    source_type   VARCHAR(32)  NOT NULL,
    source_id     BIGINT       NOT NULL,
    content       VARCHAR(500) NOT NULL,
    owner_id      BIGINT,                                -- 插入时抄下游单据的负责人，不加外键（同审计人）
    handled_by    BIGINT,
    handled_time  TIMESTAMPTZ,
    handle_remark VARCHAR(200),
    create_time   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 详情页按"类型 + id"取；首页、上游已变更页按负责人取没处理的
CREATE INDEX idx_biz_doc_notice_doc ON biz_doc_notice (doc_type, doc_id);
CREATE INDEX idx_biz_doc_notice_todo ON biz_doc_notice (owner_id) WHERE handled_time IS NULL;
COMMENT ON TABLE  biz_doc_notice IS '上游变更提醒';
COMMENT ON COLUMN biz_doc_notice.doc_type IS '收到提醒的单据类型：BizType 枚举名，如 PRODUCTION_ORDER、PURCHASE_ORDER、MRP_CALC';
COMMENT ON COLUMN biz_doc_notice.doc_id IS '收到提醒的单据 id';
COMMENT ON COLUMN biz_doc_notice.source_type IS '谁引起的：BizType 枚举名，如 ORDER_CHANGE';
COMMENT ON COLUMN biz_doc_notice.source_id IS '引起它的单据 id，如变更单 id';
COMMENT ON COLUMN biz_doc_notice.content IS '说了什么，如"订单 DD… 的 27CF001 黑色 M 码从 100 改成 80"';
COMMENT ON COLUMN biz_doc_notice.owner_id IS '提醒谁：插入时下游单据的负责人 sys_user.id（生产跟单、采购员、算料单制单人）';
COMMENT ON COLUMN biz_doc_notice.handled_by IS '谁点的"已处理" sys_user.id；空 = 待处理';
COMMENT ON COLUMN biz_doc_notice.handled_time IS '处理时间；空 = 待处理';
COMMENT ON COLUMN biz_doc_notice.handle_remark IS '怎么处理的，处理时选填';
COMMENT ON COLUMN biz_doc_notice.create_time IS '提醒时间（变更单审核的时间）';

-- 2. 多级审批规则（设置 › 审批）：单据总金额超过 over_amount 就多加一级，审批人是一个角色或一个人
CREATE TABLE biz_approval_rule (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    biz_type    VARCHAR(32)   NOT NULL,
    over_amount NUMERIC(14,2) NOT NULL,
    role_id     BIGINT REFERENCES sys_role (id),
    user_id     BIGINT REFERENCES sys_user (id),
    create_by   BIGINT,
    create_time TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- 角色、人二选一由 Service 校验并报中文提示；这里兜底，保存时漏了校验也存不进去
    CONSTRAINT ck_biz_approval_rule_approver CHECK ((role_id IS NULL) <> (user_id IS NULL))
);
CREATE INDEX idx_biz_approval_rule_type ON biz_approval_rule (biz_type);
CREATE INDEX idx_biz_approval_rule_role ON biz_approval_rule (role_id);
CREATE INDEX idx_biz_approval_rule_user ON biz_approval_rule (user_id);
COMMENT ON TABLE  biz_approval_rule IS '多级审批规则';
COMMENT ON COLUMN biz_approval_rule.biz_type IS '哪种单据：ORDER 需求订单 / PURCHASE_ORDER 采购单 / PRODUCTION_ORDER 外发加工单和成品采购单（共用）';
COMMENT ON COLUMN biz_approval_rule.over_amount IS '单据总金额超过它（不含等于）就加这一级；同一种单据门槛低的先审';
COMMENT ON COLUMN biz_approval_rule.role_id IS '审批人：角色 sys_role.id，角色里谁审都行；和 user_id 二选一';
COMMENT ON COLUMN biz_approval_rule.user_id IS '审批人：指定的人 sys_user.id；和 role_id 二选一';

-- 3. 每张单每次提交的审批流：提交时按金额和当时的规则生成，第 1 级 CURRENT、其余 WAITING；之后改规则不影响它（架构 §4.8）。
--    存成行不存成单头上的 JSONB："待我审核"要跨三种单据按当前级的审批人查。没有审计列：谁审的、什么时候审的就是 pass_by、pass_time
CREATE TABLE biz_approval_step (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    biz_type    VARCHAR(32)   NOT NULL,
    doc_id      BIGINT        NOT NULL,
    round       INT           NOT NULL,
    level_no    INT           NOT NULL,
    perm        VARCHAR(64),
    role_id     BIGINT,                                  -- 提交时从规则抄的快照，不加外键（规则删了、角色删了，审过的记录照样留着）
    user_id     BIGINT,
    over_amount NUMERIC(14,2),
    status      VARCHAR(16)   NOT NULL,
    pass_by     BIGINT,
    pass_time   TIMESTAMPTZ
);
CREATE UNIQUE INDEX uk_biz_step ON biz_approval_step (biz_type, doc_id, round, level_no);
CREATE INDEX idx_biz_step_current ON biz_approval_step (biz_type, doc_id) WHERE status = 'CURRENT';
COMMENT ON TABLE  biz_approval_step IS '单据审批流';
COMMENT ON COLUMN biz_approval_step.biz_type IS '单据类型：ORDER / PURCHASE_ORDER / PRODUCTION_ORDER';
COMMENT ON COLUMN biz_approval_step.doc_id IS '单据 id';
COMMENT ON COLUMN biz_approval_step.round IS '第几次提交，从 1 起；驳回、撤回、反审核后重新提交 +1，旧轮次留着';
COMMENT ON COLUMN biz_approval_step.level_no IS '第几级，从 1 起';
COMMENT ON COLUMN biz_approval_step.perm IS '第 1 级：审核权限码，审的时候现查（收回权限马上生效）；规则加的级为空';
COMMENT ON COLUMN biz_approval_step.role_id IS '规则加的级：审批角色 sys_role.id，提交时从规则抄过来';
COMMENT ON COLUMN biz_approval_step.user_id IS '规则加的级：指定的审批人 sys_user.id，提交时从规则抄过来';
COMMENT ON COLUMN biz_approval_step.over_amount IS '规则加的级：这一级的门槛；第 1 级为空';
COMMENT ON COLUMN biz_approval_step.status IS 'WAITING 等前面 / CURRENT 该它审 / PASSED 已过 / CANCELLED 驳回、撤回、作废后作废';
COMMENT ON COLUMN biz_approval_step.pass_by IS '审的人 sys_user.id';
COMMENT ON COLUMN biz_approval_step.pass_time IS '审的时间';
