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

    /**
     * 翻单、补单的原单（RFC 016 §2）：给了就记成 source = REPEAT。原单要在、看得到（看不到 404），不能是草稿。
     * 明细由前端照原单填好，后端不复制
     */
    private Long sourceOrderId;
}
