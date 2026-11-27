package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.module.quality.entity.QcKind;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 记质检前要看的：来源单是什么、批量、AQL 建议抽多少，以及这张来源单前几次的检验 */
@Data
public class QcSourceVO {
    private QcKind kind;
    private Long sourceId;
    private String sourceNo;
    /** 来源单的状态（DocStatus 的名字） */
    private String sourceStatus;
    private Long companyId;
    private String companyName;
    private Long partnerId;
    private String partnerName;
    private Long prdOrderId;
    private String prdOrderKind;
    private String prdOrderNo;
    private String productLine;
    private String targetDesc;
    private BigDecimal lotQty;
    private String unit;
    /** 这次是第几次 */
    private Integer attemptNo;
    /** AQL 2.5、一般检验水平 II 建议抽检数；批量不知道时空 */
    private BigDecimal aqlSample;
    /** 允收数 Ac：不合格数超过它建议判不合格 */
    private BigDecimal aqlAccept;
    /** 这张来源单前几次的检验（同一种），早的在前 */
    private List<QcRowVO> history;
}
