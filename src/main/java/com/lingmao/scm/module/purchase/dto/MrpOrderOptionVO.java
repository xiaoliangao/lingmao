package com.lingmao.scm.module.purchase.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** "选订单算料"的一行（前端 MrpOrderOption）：已审核、生产中的需求订单 */
@Data
public class MrpOrderOptionVO {
    private Long orderId;
    private String orderNo;
    private String orderType;
    private List<String> styleCodes;
    private Integer totalQty;
    private LocalDate commitDate;
    private List<CalcRef> calcs;

    /** 这张订单算过的算料单 */
    public record CalcRef(long id, String no, String status) {
    }
}
