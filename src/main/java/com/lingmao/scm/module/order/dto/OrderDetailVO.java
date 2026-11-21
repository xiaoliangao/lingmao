package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.order.entity.OrderSource;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** GET /api/orders/{id}（前端 OrderDetail）。件数是 0 的行（变更单减掉的）不在 items 里 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDetailVO extends DocHeaderVO {
    private String orderType;
    private Long customerId;
    private String customerName;
    private String customerOrderNo;
    private String contractNo;
    private String brand;
    /** 品类线，字典 product_line */
    private String productLine;
    private LocalDate orderDate;
    private LocalDate requiredDate;
    private LocalDate commitDate;
    private Long salesId;
    private String salesName;
    private Long merchId;
    private String merchName;
    private String currency;
    private String deliveryReq;
    private Integer totalQty;
    private BigDecimal totalAmount;
    private OrderSource source;
    /** 翻单、补单的原单（source = REPEAT 时有）；原单号总给，原单看不看得到另说 */
    private Long sourceOrderId;
    private String sourceOrderNo;
    private int scheduledQty;
    private int returnedQty;
    private List<OrderStyleVO> styles = List.of();
    private List<OrderItemVO> items = List.of();
    private PendingChange pendingChange;
}
