package com.lingmao.scm.module.purchase.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购明细（数据库设计 §12.3）。数量、单价、已入库、已退货都按采购单位；未入库 = 数量 −（已入库 − 已退货），现算。
 * 已入库、已退货由入库单、出库单过账回写（NEVER）；预计到货、跟进备注草稿里随表单存，审核后由 follow 动作改
 */
@Data
@TableName("pur_order_item")
public class PurchaseOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long purchaseOrderId;

    private Integer seq;

    private Long materialSkuId;

    private String purchaseUnit;

    /** 1 采购单位 = n 库存单位 */
    private BigDecimal conversionFactor;

    private BigDecimal qty;

    private BigDecimal price;

    private BigDecimal amount;

    private Long priceSourceId;

    private String priceChangeReason;

    private LocalDate lineDueDate;

    /** 来源算料行；重新算料的新单确认时改指新单的行 */
    private Long calcItemId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal receivedQty;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal returnedQty;

    private LocalDate expectDate;

    private String followNote;

    /** 净入库 = 已入库 − 已退货 */
    public BigDecimal netReceived() {
        return nz(receivedQty).subtract(nz(returnedQty));
    }

    /** 未入库 = 数量 − 净入库，不小于 0（超收了也是 0） */
    public BigDecimal pending() {
        return qty.subtract(netReceived()).max(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
