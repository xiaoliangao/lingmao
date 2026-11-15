package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 加 SKU 时的一个规格：规格自由填（如 60cm）；规格码不填就由规格算（去掉空格和末尾的 cm、mm、厘米、毫米、码，转大写） */
@Data
public class SkuSpecReq {

    @Size(max = 64, message = "规格最长 64 个字")
    private String spec;

    @Size(max = 16, message = "规格码最长 16 位")
    private String specCode;
}
