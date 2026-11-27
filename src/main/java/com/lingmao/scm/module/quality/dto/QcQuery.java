package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 质检记录列表 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QcQuery extends PageQuery {
    /** 检验种类：INCOMING 来料检验 / PRE_PRODUCTION 产前样确认 / MIDDLE 中检 / FINAL 尾期验货；不传是全部 */
    private QcKind kind;
    /** 结论：PASS 合格 / CONCESSION 让步接收 / FAIL 不合格；不传是全部 */
    private QcResult result;
    private Long companyId;
    /** 供应商或加工厂 */
    private Long partnerId;
    /** 生产单 id：只看这张生产单的（产前样、中检、它的回货的尾期验货） */
    private Long prdOrderId;
    /** 检验日期从 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;
    /** 检验日期到 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
    /** 质检单号、来源单号、生产单号、供应商的编码名称简称、货品（包含） */
    private String keyword;
}
