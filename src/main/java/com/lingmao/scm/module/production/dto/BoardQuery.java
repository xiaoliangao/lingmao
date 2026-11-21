package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 生产看板的筛选（docs/api/05 §3.7）：三个接口一样；card 只有列表看 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BoardQuery extends PageQuery {
    /** 生产单的种类：OUTSOURCE 外发加工单 / BUY 成品采购单；不传是全部 */
    private String kind;
    /** 品类线（几条线的人才有这个筛选，RFC 013 §2） */
    private String productLine;
    private Long factoryId;
    private Long merchId;
    private String brand;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDateTo;
    private boolean mine;
    /** RUNNING / WAITING / NEAR / LATE / NODE_LATE / ISSUE */
    private String card;
}
