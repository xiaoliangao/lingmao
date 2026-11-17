package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.techpack.entity.CostMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 保存时的核价部分（前端 TechPackCostForm）：按什么算、成品单价、其他费用，整份覆盖 */
@Data
public class TechPackCostForm {
    @NotNull(message = "核价要选按什么算")
    private CostMode costMode;
    /** 成品单价，元 / 件；按外发算时也照存 */
    private BigDecimal buyPrice;
    @Valid
    private List<CostItemForm> items;
}
