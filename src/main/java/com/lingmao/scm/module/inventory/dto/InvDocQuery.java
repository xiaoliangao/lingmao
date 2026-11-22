package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 入库单、出库单列表（docs/api/04 §3.2.1）。导出也用它，多一个 ids（只导选中的） */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocQuery extends PageQuery {

    @NotNull(message = "kind 必填：IN 入库单 / OUT 出库单")
    private InvDocKind kind;
    private InvDocType docType;
    private List<DocStatus> status;
    /** 出仓或入仓是它 */
    private Long warehouseId;
    private Long partnerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate docDateTo;
    /** 单号、源单号、送货单号、物流单号、物料编码 / 名称 / SKU 码、缸号；有空格 = 按几个单号精确查 */
    private String keyword;
    private boolean mine;
    private List<Long> ids;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
}
