package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 和上一张比需求变了的一行（前端 MrpDiffRow） */
@Data
@EqualsAndHashCode(callSuper = true)
public class MrpDiffRowVO extends MaterialSkuBrief {
    private String block;
    private BigDecimal fromNeedQty;
    private BigDecimal toNeedQty;
    private BigDecimal diffQty;
}
