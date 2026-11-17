package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;

import java.util.List;

/**
 * 首页"上游已变更"的一行：一张下游单据和它所有没处理的提醒（新的在前）。
 * 单据的单号、状态、负责人由 board 拿 docType + docId 问 ReferenceService.describe
 */
public record DocNoticeGroup(BizType docType, long docId, List<DocNoticeVO> notices) {
}
