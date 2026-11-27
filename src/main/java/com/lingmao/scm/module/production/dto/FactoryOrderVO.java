package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 工厂账号看的一张生产单（RFC 019 §7，GET /api/factory-orders/{id}）：手机报工页要的那几样。
 * 和内部的详情（ProductionDetailVO）分开写，字段一个个挑：单价、金额、订单、审批、条款、异常、联系人这些不给，
 * 内部详情以后加字段也不会顺带给到工厂
 *
 * @param actions         能做的：report 报进度（已下单、生产中），cut 登记裁床数
 * @param returnedQty     已回货件数（含次品）
 * @param merchName       生产跟单：有事找谁
 * @param items           颜色 × 尺码明细：件数、裁床数（可改的上下限）、已回、次品
 * @param nodes           节点：计划、实际、完成数、最近谁报的、照片
 */
public record FactoryOrderVO(Long id,
                             String no,
                             String kind,
                             String status,
                             Long companyId,
                             String styleCode,
                             String styleName,
                             AttachmentVO image,
                             String factoryShortName,
                             int totalQty,
                             int returnedQty,
                             LocalDate dueDate,
                             LocalDate expectDate,
                             Integer daysLeft,
                             RiskVO risk,
                             String currentNodeName,
                             String merchName,
                             List<String> sizes,
                             List<ProductionItemVO> items,
                             List<ProductionNodeVO> nodes,
                             List<String> actions) {
}
