package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.module.biz.dto.TaggedPageQuery;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.fg.entity.FgDocType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 成品单据列表（docs/api/07 §3.2.1）。导出也用它，多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocQuery extends TaggedPageQuery {
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "kind 必填：IN 入库单 / OUT 出库单")
    private FgDocKind kind;
    private FgDocType docType;
    private List<DocStatus> status;
    private Long warehouseId;
    private Long styleId;
    private Long factoryId;
    private Long orderId;
    /** 回货单一对多关联的完整列表，精确过滤。 */
    private Long receiptId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateTo;
    /** 单号、回货单号、生产单号、订单号、物流单号、款号；有空格 = 按几个单号精确查 */
    private String keyword;
    private boolean mine;
    @Size(max = 500, message = "最多选择当前页 500 条")
    private List<@NotNull(message = "成品单据编号不能为空") @Positive(message = "成品单据编号必须为正整数") Long> ids;
}
