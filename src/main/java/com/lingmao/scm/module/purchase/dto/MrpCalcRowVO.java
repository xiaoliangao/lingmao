package com.lingmao.scm.module.purchase.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 算料单列表的一行（前端 MrpCalcRow） */
@Data
public class MrpCalcRowVO {
    private Long id;
    private String no;
    private String status;
    /** 品类线，字典 product_line */
    private String productLine;
    private List<String> orderNos;
    private List<String> styleCodes;
    /** 自购里建议采购 > 0 的行数 */
    private int buyCount;
    /** 其中已经全转了采购的 */
    private int convertedCount;
    private LocalDate needDate;
    private Long nextCalcId;
    private String nextCalcNo;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    /** 取制单人名字用，不返回 */
    @JsonIgnore
    private Long createBy;
}
