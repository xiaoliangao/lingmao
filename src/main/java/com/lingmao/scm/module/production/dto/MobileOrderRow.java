package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.time.LocalDate;
import java.util.List;

/** 手机"我的跟单"的一张卡片（前端 MobileOrderRow） */
public record MobileOrderRow(Long id, String no, String kind, String status, String styleCode, String styleName, AttachmentVO image,
                             String factoryShortName, int totalQty, LocalDate dueDate, LocalDate expectDate, Integer daysLeft,
                             List<NodeDot> nodes, String currentNodeName, RiskVO risk) {
}
