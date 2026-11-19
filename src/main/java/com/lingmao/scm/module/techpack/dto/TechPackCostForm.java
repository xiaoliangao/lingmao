package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.CostMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 保存时的核价部分（前端 TechPackCostForm）：按什么算、加工费、成品单价、其他费用，整份覆盖 */
@Data
public class TechPackCostForm {
    @NotNull(message = "核价要选按什么算")
    private CostMode costMode;
    /** 成品单价，元 / 件；按外发算时也照存 */
    @DecimalMin(value = "0", message = "成品单价不能是负数")
    private BigDecimal buyPrice;
    /** 加工费，元 / 件（按外发算时用）；空 = 用价格档案里的默认。按成品采购算时也照存 */
    @DecimalMin(value = "0", message = "加工费不能是负数")
    private BigDecimal laborPrice;
    @Valid
    private List<CostItemForm> items;
}
