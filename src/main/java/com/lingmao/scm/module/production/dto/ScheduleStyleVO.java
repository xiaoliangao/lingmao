package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.math.BigDecimal;
import java.util.List;

/** 排单对话框里一个款（前端 ScheduleStyle）：只列还有没排完的款；laborPrice 是锁定版本的加工费（核价按外发算的口径，RFC 011 §3.1） */
public record ScheduleStyleVO(long styleId, String styleCode, String styleName, AttachmentVO image, long techPackId, int techPackVersion,
                              BigDecimal laborPrice, Long defaultFactoryId, String defaultFactoryName, List<String> sizes,
                              List<ColorOption> colors, List<ScheduleSkuVO> skus) {
}
