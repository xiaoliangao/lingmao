package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.entity.TechPackStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 给需求订单用的款式（docs/api/02 §3.4）：录单矩阵、订单详情、变更单都按它画。颜色、SKU 停用的也在（灰掉、标原因），
 * 能不能下单由订单那边判断。
 *
 * @param image  主图（款式图第一张）；没有是 null
 * @param sizes  尺码组的尺码，按顺序：矩阵的列
 * @param colors 款上的颜色，按款上的顺序
 * @param skus   全部 SKU，按颜色、尺码的顺序
 */
public record StyleOrderInfo(long id,
                             String code,
                             String name,
                             String aliasName,
                             AttachmentVO image,
                             StyleStatus status,
                             TechPackStatus techPackStatus,
                             List<String> sizes,
                             List<Color> colors,
                             List<Sku> skus,
                             BigDecimal tagPrice,
                             Long defaultFactoryId) {

    /** 款上的一个颜色；enabled = 在这个款上没停用 */
    public record Color(long id, String code, String name, String hex, boolean enabled) {
    }

    /** code 是"款号-颜色码-尺码" */
    public record Sku(long id, long colorId, String sizeCode, String code, boolean enabled) {
    }
}
