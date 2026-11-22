package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 盘点单列表（docs/api/04 §3.5.1）；导出多一个 ids */
@Data
@EqualsAndHashCode(callSuper = true)
public class CountQuery extends PageQuery {
    /** 单号、仓库、盘点人、物料编码 / 名称 / SKU 码 */
    private String keyword;
    private List<DocStatus> status;
    private Long warehouseId;
    private CountPurpose purpose;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate countDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate countDateTo;
    private boolean mine;
    private List<Long> ids;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
}
