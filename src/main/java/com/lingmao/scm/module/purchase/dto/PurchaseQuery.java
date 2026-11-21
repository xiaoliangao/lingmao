package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 采购单列表、导出（docs/api/03 §3.3.1）；导出多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class PurchaseQuery extends PageQuery {
    /** 采购单号、供应商、物料编码 / 名称 / SKU 码、订单号、款号、算料单号；一次查几个时单号、款号、物料编码精确查 */
    private String keyword;
    private List<DocStatus> status;
    /** 采购类型，取值见枚举；不传是全部 */
    private PurchaseType purchaseType;
    /** 品类线（几条线的人才有这个筛选，RFC 013 §2） */
    private String productLine;
    private Long supplierId;
    private Long buyerId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateTo;
    private boolean mine;
    private boolean auditForMe;
    /** "待我审批"轮到我的单据 id：Service 查好了填进来，不用前端传的 */
    @JsonIgnore
    private List<Long> approvalIds = List.of();
    private List<Long> ids;
}
