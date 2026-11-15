package com.lingmao.scm.module.biz.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 操作记录（biz_doc_log，数据库设计 §4.1）：单据、主数据每做一个动作写一行，详情页"操作记录"页签读它。
 * 只插不改，所以没有修改人、修改时间，也不继承 AuditEntity；记录挂在哪条上用 doc_type + doc_id，没法加外键，
 * 只有 DocLogService 写它，删那条记录时由它的 Service 调 deleteByDoc 一起删。
 * <p>
 * 表里还有一列 extra（JSONB，附加信息快照，如反审核撤销了哪些回写），这里先不映射：
 * 写它要一个 JSONB 的类型处理器，第一个用到的（第 3 批的反审核）连同处理器一起加，legacy_attrs 也要同一个处理器。
 */
@Data
@TableName("biz_doc_log")
public class DocLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private BizType docType;
    private Long docId;

    /** 动作：create、submit、audit、reject、export-pdf……和 StatusFlow 的动作名一样，最长 16 个字符 */
    private String action;

    /** 动作前后的状态，存状态枚举的名字；状态没变的动作（导出、多级审批的中间级）两个都空 */
    private String fromStatus;
    private String toStatus;

    /** 驳回原因、变更原因、"第 1 级通过"，最长 500 */
    private String remark;

    /** 操作人，当前登录用户；没有登录用户（定时任务）为空 */
    private Long operatorId;

    /** 插入时由 AuditMetaObjectHandler 按注入的 Clock 填，和别的表的创建时间一个来源 */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
