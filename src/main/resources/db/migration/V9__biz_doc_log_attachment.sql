-- V9：单据公共能力里第 2 批主数据就要用的两张表（数据库设计 §4.1、§4.2，RFC 001 §14）。变更提醒 biz_doc_notice 随第 3 批的第一张单据建。
-- 两张表都用"类型 + id"指向各种单据和主数据（doc_type 存 BizType 的枚举名），没法加外键：
-- 只有 biz 的 DocLogService、AttachmentService 写它们，删那条记录时由它的 Service 在同一个事务里一起删（RFC 003 §2）。
-- 新业务表，不用逻辑删除（架构 §6）。

-- 1. 操作记录：每个动作一行，只插不改，所以没有修改人、修改时间
CREATE TABLE biz_doc_log (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_type    VARCHAR(32)  NOT NULL,
    doc_id      BIGINT       NOT NULL,
    action      VARCHAR(16)  NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    remark      VARCHAR(500),
    extra       JSONB,
    operator_id BIGINT,                                  -- 审计人一类，不加外键（数据库设计 §1.4）
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 详情页按"类型 + id"取、按 id 倒着排，一个索引都覆盖了
CREATE INDEX idx_biz_doc_log_doc ON biz_doc_log (doc_type, doc_id, id);
COMMENT ON TABLE  biz_doc_log IS '操作记录';
COMMENT ON COLUMN biz_doc_log.doc_type IS '挂在哪种单据、主数据上：BizType 枚举名，如 SUPPLIER、PURCHASE_ORDER';
COMMENT ON COLUMN biz_doc_log.doc_id IS '那条单据、主数据的 id';
COMMENT ON COLUMN biz_doc_log.action IS '动作：create、submit、audit、reject、void、close、export-pdf……和 StatusFlow 的动作名一样';
COMMENT ON COLUMN biz_doc_log.from_status IS '动作前的状态（枚举名）；状态没变的动作为空';
COMMENT ON COLUMN biz_doc_log.to_status IS '动作后的状态（枚举名）；状态没变的动作为空';
COMMENT ON COLUMN biz_doc_log.remark IS '驳回原因、变更原因、"第 1 级通过"';
COMMENT ON COLUMN biz_doc_log.extra IS '附加信息快照，如反审核撤销了哪些回写';
COMMENT ON COLUMN biz_doc_log.operator_id IS '操作人 sys_user.id；系统触发的为空';

-- 2. 附件和图片：款式图、物料图、报工照片、签回件……都在这里，不再给每种图建表（数据库设计 §1.7）。
--    文件本身已经由 /api/files 存好了，这里只记挂在哪、第几个；地址存不带签名的 /files/...，返回时再签（架构 §8）
CREATE TABLE biz_attachment (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    doc_type    VARCHAR(32)  NOT NULL,
    doc_id      BIGINT       NOT NULL,
    category    VARCHAR(16)  NOT NULL DEFAULT 'FILE',
    sort        INT          NOT NULL DEFAULT 0,
    file_url    VARCHAR(255) NOT NULL,
    thumb_url   VARCHAR(255),
    file_name   VARCHAR(255) NOT NULL,
    file_size   BIGINT,
    create_by   BIGINT,
    create_time TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by   BIGINT,
    update_time TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- 详情页按"类型 + id + 分类"取、按顺序排；列表页取每条的主图（DISTINCT ON）也走它
CREATE INDEX idx_biz_attachment_doc ON biz_attachment (doc_type, doc_id, category, sort);
COMMENT ON TABLE  biz_attachment IS '附件';
COMMENT ON COLUMN biz_attachment.doc_type IS '挂在哪种单据、主数据上：BizType 枚举名，如 STYLE、MATERIAL';
COMMENT ON COLUMN biz_attachment.doc_id IS '那条单据、主数据的 id；全公司一份的设置（工艺标准文件）是 0';
COMMENT ON COLUMN biz_attachment.category IS '分组：IMAGE 图片 / FILE 文件；款式设计附件 DESIGN；资料包工艺指示插图用段代码';
COMMENT ON COLUMN biz_attachment.sort IS '组内顺序，从 0 起；IMAGE 里最小的是主图';
COMMENT ON COLUMN biz_attachment.file_url IS '文件地址 /files/...，不带签名';
COMMENT ON COLUMN biz_attachment.thumb_url IS '缩略图地址（文件名多一个 _s），只有 jpg、png 有';
COMMENT ON COLUMN biz_attachment.file_name IS '原始文件名';
COMMENT ON COLUMN biz_attachment.file_size IS '字节数';
