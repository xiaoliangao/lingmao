package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/** 成品库存明细、按款（docs/api/07 §3.6） */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgStockQuery extends PageQuery {
    @NotNull(message = "请选主体")
    private Long companyId;
    private Long warehouseId;
    private Long styleId;
    /** 款号、款名、SKU 码 */
    private String keyword;
    /** 列出 0 的行；默认不列 */
    private boolean includeZero;
    /** 导出选中的（明细） */
    @Size(max = 500, message = "最多选择当前页 500 条")
    private List<@NotNull(message = "库存明细编号不能为空") @Positive(message = "库存明细编号必须为正整数") Long> ids;
}
