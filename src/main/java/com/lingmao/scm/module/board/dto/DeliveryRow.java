package com.lingmao.scm.module.board.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocRef;

import java.time.LocalDate;

/**
 * 近期交期的一行（GET /api/board/home/deliveries，docs/api/06 §2.4）：7 天内要交、或者已经逾期的需求订单、生产单。
 *
 * @param sub      需求订单：款名（几个款用顿号连）；生产单：款名 · 加工厂
 * @param dueDate  需求订单 = 承诺交期 ?? 要求交期；生产单 = 预计交期 ?? 交期
 * @param daysLeft 今天到交期还有几天，逾期是负数
 * @param doneQty  已回良品（回货 − 次品）
 */
public record DeliveryRow(DocRef ref, String sub, AttachmentVO image, LocalDate dueDate, long daysLeft, long doneQty, long totalQty) {
}
