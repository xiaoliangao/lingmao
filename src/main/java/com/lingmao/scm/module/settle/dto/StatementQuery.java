package com.lingmao.scm.module.settle.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

/** 对账单列表（docs/api/09 §2.1） */
@Data
@EqualsAndHashCode(callSuper = true)
public class StatementQuery extends PageQuery {
    /** 状态，几个一起查；不传是全部（含已作废） */
    private List<StatementStatus> status;
    /** 主体 */
    private Long companyId;
    /** 供应商或加工厂 */
    private Long partnerId;
    /** 期间和这一段有重叠的：期间止 ≥ periodFrom */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate periodFrom;
    /** 期间和这一段有重叠的：期间起 ≤ periodTo */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate periodTo;
    /** 对账单号、供应商的编码、名称、简称（包含） */
    private String keyword;
    /** true = 只看我建的 */
    private boolean mine;
}
