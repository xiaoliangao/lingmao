package com.lingmao.scm.module.quality.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一条质检记录（列表、返修列表、详情、验货历史共用） */
@Data
public class QcRowVO {
    private Long id;
    private String no;
    private Long companyId;
    private String companyName;
    private QcKind kind;
    /** 来料检验的采购入库单 */
    private Long invDocId;
    /** 尾期验货的回货单 */
    private Long prdReceiptId;
    /** 生产单：产前样、中检的，尾期验货是回货单的 */
    private Long prdOrderId;
    /** 生产单的 kind：OUTSOURCE 外发加工单 / BUY 成品采购单，前端拼链接用 */
    private String prdOrderKind;
    private String prdOrderNo;
    /** 来源单号：采购入库单号、回货单号；产前样、中检是生产单号 */
    private String sourceNo;
    /** 同一张来源单、同一种检验的第几次 */
    private Integer attemptNo;
    private Long partnerId;
    private String partnerCode;
    private String partnerName;
    private String productLine;
    /** 验的是什么：物料名、款号款名 */
    private String targetDesc;
    /** 批量 */
    private BigDecimal lotQty;
    /** 批量的单位（字典 unit） */
    private String unit;
    private LocalDate inspectDate;
    private BigDecimal sampleQty;
    private BigDecimal defectQty;
    /** 允收数（AQL 2.5） */
    private BigDecimal acceptQty;
    private QcResult result;
    /** 问题类型（字典 qc_defect_type） */
    private List<String> defectTypes;
    private String note;
    private String reworkNote;
    private Long reworkBy;
    private String reworkByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime reworkTime;
    private Long reworkDoneBy;
    private String reworkDoneByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime reworkDoneTime;
    private Long deductionId;
    /** 质量扣款金额（要扣款登记的查看权限） */
    private BigDecimal deductAmount;
    /** 扣款进了哪张没作废的对账单：进了的扣款不能改、删 */
    private String deductStatementNo;
    private List<AttachmentVO> photos;
    private Integer version;
    /** 检验人 */
    private Long createBy;
    private String inspectorName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
