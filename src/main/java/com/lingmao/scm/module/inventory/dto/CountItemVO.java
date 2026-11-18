package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 盘点明细的一行（前端 CountItem） */
@Data
@EqualsAndHashCode(callSuper = true)
public class CountItemVO extends MaterialSkuBrief {
    private Long id;
    private Integer seq;
    private BigDecimal bookQty;
    private Long bookRevision;
    private BigDecimal countedQty;
    /** 实盘 − 账面；实盘没填是 null */
    private BigDecimal diffQty;
    /** 已审核才有：过账时实际调了多少（= diffQty）；没审核是 null */
    private BigDecimal adjustedQty;
    private String remark;
    /** 这一行过过账（有库存日志）：改草稿时不能删 */
    private boolean posted;
}
