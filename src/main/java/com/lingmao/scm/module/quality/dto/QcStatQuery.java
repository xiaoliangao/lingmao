package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.module.quality.entity.QcKind;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** 供应商质量：按检验日期统计 */
@Data
public class QcStatQuery {
    /** 检验日期从；不传是 90 天前 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFrom;
    /** 检验日期到；不传是今天 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateTo;
    private Long companyId;
    /** 只算一种检验；不传是全部 */
    private QcKind kind;
}
