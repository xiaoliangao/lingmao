package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.entity.ApprovalStepStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 审批块里的一级（前端 types/biz.ts 的 ApprovalLevel）。
 *
 * @param approverLabel 谁审：第 1 级"有审核权限的人"，规则加的是角色名或人名
 * @param overAmount    规则加的级：金额超过多少才有这一级；第 1 级 null
 */
public record ApprovalLevel(int levelNo,
                            String approverLabel,
                            BigDecimal overAmount,
                            ApprovalStepStatus status,
                            String passByName,
                            @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                            OffsetDateTime passTime) {
}
