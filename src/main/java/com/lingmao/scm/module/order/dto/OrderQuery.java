package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 需求订单列表（docs/api/02 §3.2）；counts 不看 status，导出多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderQuery extends PageQuery {
    /** 单号、合同号、款号；一次查几个时单号、款号精确查 */
    private String keyword;
    private List<DocStatus> status;
    private String orderType;
    private String brand;
    private Long salesId;
    private Long merchId;
    /** 含这个款的 */
    private Long styleId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate orderDateTo;
    /** 待排单：已审核、一件都还没排的 */
    private boolean unscheduled;
    private boolean mine;
    private List<Long> ids;
}
