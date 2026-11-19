package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.fg.entity.FgDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 手工新建（POST /api/fg-docs）：其他入库、发货出库、其他出库 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocCreateReq extends FgDocForm {
    /** 发货出库要和订单的主体一样（ship-prefill 带出） */
    @NotNull(message = "请选主体")
    private Long companyId;
    @NotNull(message = "请选类型")
    private FgDocType docType;
    /** 发货出库必填 */
    private Long orderId;
}
