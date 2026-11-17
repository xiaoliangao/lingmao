package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** POST /api/orders（前端 OrderCreateForm）：多一组附件 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderCreateReq extends OrderForm {
    private List<@Valid AttachmentReq> files = new ArrayList<>();
}
