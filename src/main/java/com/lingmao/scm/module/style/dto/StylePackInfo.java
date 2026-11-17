package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.module.style.entity.StyleStatus;

import java.math.BigDecimal;
import java.util.List;

/**
 * 给资料包用的款式（RFC 005 §4、RFC 007 §6.2）：资料包的单头、尺码列、配料的款色、核价的吊牌价和默认加工厂、PDF 的表头都从这里取。
 *
 * @param colorIds 款式上启用的颜色，按款式上的顺序；随款色配料、"按颜色"视图、核价默认的第一个颜色都按它
 * @param status   停用的款式不能再建、改、生效资料包（草稿款可以）
 * @param productName 品名、composition 成分：PDF 表头
 */
public record StylePackInfo(long id,
                            String code,
                            String name,
                            String aliasName,
                            Integer year,
                            String season,
                            String sampleNo,
                            long sizeGroupId,
                            List<Long> colorIds,
                            BigDecimal tagPrice,
                            Long defaultFactoryId,
                            String productName,
                            String composition,
                            StyleStatus status) {
}
