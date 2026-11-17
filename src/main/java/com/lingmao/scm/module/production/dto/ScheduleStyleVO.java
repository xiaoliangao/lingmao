package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.math.BigDecimal;
import java.util.List;

/** 排单对话框里一个款（前端 ScheduleStyle）：只列还有没排完的款；laborTotal 是锁定版本的工序工价合计 */
public record ScheduleStyleVO(long styleId, String styleCode, String styleName, AttachmentVO image, long techPackId, int techPackVersion,
                              BigDecimal laborTotal, Long defaultFactoryId, String defaultFactoryName, List<String> sizes,
                              List<ColorOption> colors, List<ScheduleSkuVO> skus) {
}
