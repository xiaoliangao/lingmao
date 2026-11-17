package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** 新建盘点单（前端 CountCreateForm）：按范围带出明细、拍下账面数 */
@Data
public class CountCreateReq {
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "请选仓库")
    private Long warehouseId;
    @NotNull(message = "请选用途")
    private CountPurpose purpose;
    @NotNull(message = "请选范围")
    private CountScope scope;
    /** scope = CATEGORY 时必选（二级分类）；别的发 [] */
    private List<Long> categoryIds = List.of();
    /** scope = PICKED 时手选的 SKU；期初录入可以是空的，日常盘点至少一个 */
    @Size(max = 2000, message = "一次最多手选 2000 种")
    private List<Long> skuIds = List.of();
    @NotNull(message = "请填盘点日期")
    private LocalDate countDate;
    @NotNull(message = "请选盘点人")
    private Long counterId;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
}
