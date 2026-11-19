package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 保存草稿（PUT /api/tech-packs/{id}，前端 TechPackSaveForm）：整版覆盖，单头能改的几项 + 物料清单的全部行 + 工艺单文件，数组顺序就是行号 */
@Data
public class TechPackSaveReq {
    @NotNull(message = "缺少 versionLock，请刷新后再保存")
    private Integer versionLock;
    @Size(max = 500, message = "本版说明最长 500 个字")
    private String versionNote;
    @DecimalMin(value = "0", message = "排版利用率不能是负数")
    @DecimalMax(value = "100", message = "排版利用率不能超过 100%")
    private BigDecimal utilPct;
    @Valid
    private List<BomItemForm> bomItems;
    /** 工艺单页签的文件，按组、组内按顺序；null = 这次不动文件 */
    @Valid
    @Size(max = 60, message = "一个版本最多挂 60 个文件")
    private List<PackFileForm> files;
    /** 核价的手填部分；null = 这次不动核价（没有看成本权限的人一律发 null，发了也不用） */
    @Valid
    private TechPackCostForm cost;
}
