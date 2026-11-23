package com.lingmao.scm.module.fg.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 新建、改草稿共用的单头 + 明细（前端 FgDocForm 去掉 version） */
@Data
public class FgDocForm {
    @NotNull(message = "请选主体")
    private Long companyId;
    /** 入库单是入仓，出库单是出仓 */
    @NotNull(message = "请选仓库")
    private Long warehouseId;
    @NotNull(message = "请填日期")
    private LocalDate docDate;
    @Size(max = 64, message = "原因最多 64 个字")
    private String reason;
    @Size(max = 255, message = "发到哪最多 255 个字")
    private String shipTo;
    @Size(max = 64, message = "物流单号最多 64 个字")
    private String logisticsNo;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    @NotEmpty(message = "明细至少一行")
    @Size(max = 2000, message = "一张单最多 2000 行")
    private List<@Valid FgDocItemReq> items;
}
