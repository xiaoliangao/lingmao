package com.lingmao.scm.module.biz.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 上游变更提醒（biz_doc_notice，数据库设计 §4.3、架构 §4.4）：需求订单的变更单审核后，给每张受影响的下游单据插一条。
 * 下游详情顶上的黄条、首页"上游已变更"都读它。插了以后只有"处理"这一个动作改它（handled_by、handled_time、handle_remark），
 * 所以不继承 AuditEntity；记录挂在哪张单上用 doc_type + doc_id，没法加外键，只有 DocNoticeService 写它
 */
@Data
@TableName("biz_doc_notice")
public class DocNotice {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 收到提醒的单据 */
    private BizType docType;
    private Long docId;

    /** 引起它的单据（变更单） */
    private BizType sourceType;
    private Long sourceId;

    /** 说了什么，最长 500 */
    private String content;

    /** 提醒谁：插入时下游单据的负责人 */
    private Long ownerId;

    /** 谁、什么时候处理的、怎么处理的；handled_time 空 = 待处理 */
    private Long handledBy;
    private OffsetDateTime handledTime;
    private String handleRemark;

    /** 插入时由 AuditMetaObjectHandler 按注入的 Clock 填 */
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createTime;
}
