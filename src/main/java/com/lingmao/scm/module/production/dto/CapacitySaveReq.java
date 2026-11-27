package com.lingmao.scm.module.production.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 登记产能（POST /api/factory-capacities）：同一家厂、同一个品类、同一个月已经有就覆盖 */
@Data
public class CapacitySaveReq {
    /** 加工厂（成衣厂、加工厂） */
    @NotNull(message = "请选加工厂")
    private Long factoryId;
    /** 品类，任何一级都行，管它和它下面的；不填 = 这家厂所有款合计 */
    private Long categoryId;
    /** 从哪个月起，yyyy-MM；到下一次登记为止都按这个数 */
    @NotNull(message = "请选从哪个月起")
    @Pattern(regexp = "\\d{4}-\\d{2}", message = "月份写成 2026-12 这样")
    private String month;
    /** 日产能，件 / 天（按自然日，含周末，填平均每天能出多少） */
    @NotNull(message = "请填日产能")
    @Min(value = 1, message = "日产能至少 1 件")
    @Max(value = 1_000_000, message = "日产能太大了")
    private Integer dailyQty;
    @Size(max = 255, message = "备注最长 255 个字")
    private String remark;
}
