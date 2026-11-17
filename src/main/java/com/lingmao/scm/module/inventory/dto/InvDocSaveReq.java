package com.lingmao.scm.module.inventory.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 改草稿（PUT /api/inventory-docs/{id}），整体覆盖。类型、kind、源单建好就不能改，不在这里 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InvDocSaveReq extends InvDocForm {
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
}
