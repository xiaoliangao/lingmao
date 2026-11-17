package com.lingmao.scm.module.techpack.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 核价的"其他费用"一项（前端 CostItem） */
@Data
public class CostItemVO {
    private Long id;
    private Integer seq;
    private String name;
    private BigDecimal amount;
    private String remark;
    /** 研发版里同名那一项的金额；没有是 null */
    private BigDecimal devAmount;
}
