package com.lingmao.scm.module.techpack.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 资料包的主人（RFC 007 §6.2）：建版本、生效、修订、核价、PDF 要的款式信息都从这里取，不在各处直接问 StyleService。
 * 一期主人都是款式（PackOwners 用 StyleService 拼）；二期研发 BOM 挂样衣时，由 dev 实现 techpack 定义的 SamplePackOwner 给同一个记录，
 * 只在 PackOwners 一处加分支。
 *
 * @param colorIds     启用的颜色，按顺序
 * @param lockedReason 不能再建、改、生效资料包的原因（款式停用了）；null = 能改
 */
public record PackOwner(String code, String name, String aliasName, Integer year, String season, String sampleNo,
                        long sizeGroupId, List<Long> colorIds, BigDecimal tagPrice, Long defaultFactoryId,
                        String lockedReason) {

    public boolean locked() {
        return lockedReason != null;
    }
}
