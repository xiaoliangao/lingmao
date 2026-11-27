package com.lingmao.scm.module.quality.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.StringArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 质检记录（RFC 019 §6，数据库设计 §17.3）：一次检验一条，不审核，保存就算数。检验人是 create_by。
 * 来源三选一（按 kind）：采购入库单 inv_doc_id、回货单 prd_receipt_id（同时记它的生产单）、生产单 prd_order_id。
 * 改都用 LambdaUpdateWrapper 一列一列 set（update(null, wrapper)），不整条写回。
 * 回货的质检状态由这里推出来：没验 / 返修完成 = 待检，最近一次的结论 = 合格、让步接收、不合格，安排了返修没完成 = 返修中
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "qc_inspection", autoResultMap = true)
public class QcInspection extends AuditEntity {

    private String no;

    private Long companyId;

    private QcKind kind;

    private Long invDocId;

    private Long prdReceiptId;

    private Long prdOrderId;

    /** 同一张来源单、同一种检验的第几次 */
    private Integer attemptNo;

    private Long partnerId;

    /** 生产单的品类线；来料检验空 */
    private String productLine;

    private String targetDesc;

    private BigDecimal lotQty;

    private String unit;

    private LocalDate inspectDate;

    private BigDecimal sampleQty;

    private BigDecimal defectQty;

    private BigDecimal acceptQty;

    private QcResult result;

    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> defectTypes;

    private String note;

    private String reworkNote;

    private Long reworkBy;

    private OffsetDateTime reworkTime;

    private Long reworkDoneBy;

    private OffsetDateTime reworkDoneTime;

    /** 同时登记的质量扣款（stl_deduction） */
    private Long deductionId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;
}
