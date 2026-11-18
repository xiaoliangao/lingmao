package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.inventory.entity.InvDocType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 由源单生成（POST …/from-purchase/{id}、…/from-production/{id}，前端 InvDocFromSourceReq）。单头没填的按带出的默认值建。
 * items 和 itemIds 二选一：对话框填好了数发 items；"待到料"勾几行直接入库发 itemIds（每行按建议数建），两个都是 null = 源单上还能入的全部行。
 * post = true：建好马上审核过账，一个事务，审核不过连单据也不建
 */
@Data
public class InvDocFromSourceReq {

    @NotNull(message = "缺少 clientActionId，请刷新后重新操作")
    private UUID clientActionId;

    @NotNull(message = "请选类型")
    private InvDocType docType;
    private LocalDate docDate;
    private Long fromWarehouseId;
    private Long toWarehouseId;
    @Size(max = 64, message = "送货单号最多 64 个字")
    private String supplierDeliveryNo;
    @Size(max = 64, message = "物流单号最多 64 个字")
    private String logisticsNo;
    @Size(max = 64, message = "原因最多 64 个字")
    private String reason;
    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
    private List<@Valid Item> items;
    private List<Long> itemIds;
    private boolean post;

    /** 对话框填好的一行。数量的单位：采购的两种是采购单位，领料、退料是库存单位；0 的行不建 */
    @Data
    public static class Item {
        private Long sourceItemId;
        @NotNull(message = "明细要选物料")
        private Long materialSkuId;
        @NotNull(message = "请填数量")
        @DecimalMin(value = "0", message = "数量不能是负数")
        @Digits(integer = 11, fraction = 3, message = "数量最多 3 位小数")
        private BigDecimal qty;
        @Size(max = 32, message = "缸号最多 32 个字")
        private String dyeLot;
        @Min(value = 0, message = "卷数不能是负数")
        private Integer rolls;
        @Size(max = 255, message = "超领原因最多 255 个字")
        private String overReason;
        @Size(max = 255, message = "备注最多 255 个字")
        private String remark;
    }
}
