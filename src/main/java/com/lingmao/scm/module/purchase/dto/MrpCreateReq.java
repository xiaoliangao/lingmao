package com.lingmao.scm.module.purchase.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** 选订单算料（POST /api/mrp-calcs） */
@Data
public class MrpCreateReq {
    @NotEmpty(message = "请选要算料的订单")
    @Size(max = 100, message = "一次最多算 100 张订单")
    private List<Long> orderIds;
}
