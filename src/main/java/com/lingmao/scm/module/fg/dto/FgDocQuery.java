package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 成品单据列表（docs/api/07 §3.2.1）。导出也用它，多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocQuery extends PageQuery {
    /** IN 入库单 / OUT 出库单，必填 */
    @NotNull(message = "kind 必填：IN 入库单 / OUT 出库单")
    private FgDocKind kind;
    /** 单据类型，取值见枚举；不传是这一边（入库或出库）的全部 */
    private FgDocType docType;
    private List<DocStatus> status;
    private Long warehouseId;
    private Long styleId;
    private Long factoryId;
    private Long orderId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateTo;
    /** 单号、回货单号、生产单号、订单号、物流单号、款号；有空格 = 按几个单号精确查 */
    private String keyword;
    private boolean mine;
    private List<Long> ids;
}
