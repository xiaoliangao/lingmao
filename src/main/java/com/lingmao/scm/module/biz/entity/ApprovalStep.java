package com.lingmao.scm.module.biz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 一张单一次提交里的一级审批（biz_approval_step，数据库设计 §4.5、架构 §4.8）。提交时生成一轮（round），一级一行。
 * 第 1 级记审核权限码（perm），审的时候现查当前用户有没有；规则加的级记提交时抄来的角色或人、门槛。
 * 没有审计列：只有 ApprovalService 插、改它，谁审的、什么时候审的就是 pass_by、pass_time
 */
@Data
@TableName("biz_approval_step")
public class ApprovalStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private BizType bizType;
    private Long docId;

    /** 第几次提交，从 1 起 */
    private Integer round;

    /** 第几级，从 1 起 */
    private Integer levelNo;

    /** 第 1 级：审核权限码；规则加的级为空 */
    private String perm;

    /** 规则加的级：审批角色、指定的人（二选一） */
    private Long roleId;
    private Long userId;

    /** 规则加的级的门槛；第 1 级为空 */
    private BigDecimal overAmount;

    private ApprovalStepStatus status;
    private Long passBy;
    private OffsetDateTime passTime;
}
