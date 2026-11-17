package com.lingmao.scm.module.order.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 录单、提变更时画矩阵要的款式信息（前端 OrderStyleOption，docs/api/02 §3.4）。不能下单的款照样返回，problem 写原因
 *
 * @param colors 款上的颜色，按顺序；停用的也在，orderable = false
 * @param problem 不能下单的原因；能下是 null
 */
public record OrderStyleOptionVO(long styleId,
                                 String code,
                                 String name,
                                 String aliasName,
                                 AttachmentVO image,
                                 StyleStatus status,
                                 TechPackStatus techPackStatus,
                                 Long currentPackId,
                                 Integer currentPackVersion,
                                 List<String> sizes,
                                 List<Color> colors,
                                 List<Sku> skus,
                                 BigDecimal tagPrice,
                                 String problem) {

    /** 一个颜色（矩阵的一行）：不能下时 reason 写为什么 */
    public record Color(long id, String code, String name, String hex, boolean orderable, String reason) {
    }

    /** 一个 SKU（矩阵的一格）：status 1 启用、0 停用，停用的那一格灰掉 */
    public record Sku(long id, long colorId, String sizeCode, String code, int status) {
    }
}
