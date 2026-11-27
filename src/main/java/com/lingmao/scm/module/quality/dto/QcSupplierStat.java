package com.lingmao.scm.module.quality.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 一家供应商（加工厂）这段时间的检验 */
@Data
public class QcSupplierStat {
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    /** 检验次数 */
    private Long inspectCount;
    private Long passCount;
    private Long concessionCount;
    private Long failCount;
    /** 抽检数合计（只算填了抽检数的） */
    private BigDecimal sampleQty;
    private BigDecimal defectQty;
    /** 质量扣款合计（要扣款登记的查看权限） */
    private BigDecimal deductAmount;
    /** 最常见的问题类型（字典值），多的在前，最多 3 个 */
    private List<String> topDefects;
}
