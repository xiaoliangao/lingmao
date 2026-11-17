package com.lingmao.scm.module.techpack.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 保存时一项其他费用 */
@Data
public class CostItemForm {
    @NotBlank(message = "其他费用的名称不能为空")
    @Size(max = 100, message = "其他费用的名称最长 100 个字")
    private String name;
    @NotNull(message = "其他费用要填金额")
    private BigDecimal amount;
    @Size(max = 255, message = "其他费用的备注最长 255 个字")
    private String remark;
}
