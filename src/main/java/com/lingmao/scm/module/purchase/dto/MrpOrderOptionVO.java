package com.lingmao.scm.module.purchase.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** "选订单算料"的一行（前端 MrpOrderOption）：已审核、生产中的需求订单 */
@Data
public class MrpOrderOptionVO {
    private Long orderId;
    /** 订单的主体：建算料单时带上它（本人范围的采购打不开别人的订单详情，不能靠详情取主体） */
    private Long companyId;
    private String orderNo;
    private String orderType;
    /** 品类线：一张算料单只能选一条线的订单 */
    private String productLine;
    private List<String> styleCodes;
    private Integer totalQty;
    private LocalDate commitDate;
    private List<CalcRef> calcs;

    /** 这张订单算过的算料单 */
    public record CalcRef(long id, String no, String status) {
    }
}
