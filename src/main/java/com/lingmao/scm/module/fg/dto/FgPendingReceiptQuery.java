package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 待入库（docs/api/07 §3.3.1） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgPendingReceiptQuery extends PageQuery {
    @NotNull(message = "请选主体")
    private Long companyId;
    private Long factoryId;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate receiptDateFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate receiptDateTo;
    /** 回货单号、生产单号、款号 */
    private String keyword;
}
