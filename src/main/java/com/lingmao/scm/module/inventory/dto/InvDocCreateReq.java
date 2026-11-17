package com.lingmao.scm.module.inventory.dto;

import com.lingmao.scm.module.inventory.entity.InvDocType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 手工新建（POST /api/inventory-docs）：只收不挂源单的类型（退料入库、其他入库、调拨、其他出库） */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocCreateReq extends InvDocForm {
    @NotNull(message = "请选类型")
    private InvDocType docType;
}
