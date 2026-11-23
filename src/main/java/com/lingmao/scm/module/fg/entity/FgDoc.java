package com.lingmao.scm.module.fg.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.entity.DocEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 成品入库单、成品出库单（RFC 008 §2）。状态：草稿 / 已审核（审核即过账）/ 已作废（过过账又反审核回来的才能作废）。
 * 入库单只有入仓，出库单只有出仓（表上有 CHECK）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fg_doc")
public class FgDoc extends DocEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private FgDocKind kind;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private FgDocType docType;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private DocStatus status;

    private Long fromWarehouseId;

    private Long toWarehouseId;

    /** 回货入库：回货单；建单时定 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long prdReceiptId;

    /** 发货出库：需求订单；建单时定 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long orderId;

    private LocalDate docDate;

    /** 其他入库、其他出库：字典值 */
    private String reason;

    private String shipTo;

    private String logisticsNo;

    /** 入库单是入仓，出库单是出仓 */
    public Long warehouseId() {
        return kind == FgDocKind.IN ? toWarehouseId : fromWarehouseId;
    }
}
