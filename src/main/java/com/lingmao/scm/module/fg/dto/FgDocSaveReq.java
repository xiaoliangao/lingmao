package com.lingmao.scm.module.fg.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 改草稿（PUT /api/fg-docs/{id}），整单覆盖。类型、主体、回货单、订单建好就不能改 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FgDocSaveReq extends FgDocForm {
    @NotNull(message = "缺少 version，请刷新后再试")
    private Integer version;
}
