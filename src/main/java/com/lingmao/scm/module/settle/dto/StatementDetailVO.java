package com.lingmao.scm.module.settle.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;
import java.util.List;

/** 对账单详情 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StatementDetailVO extends StatementRowVO {
    private Integer version;
    /** 供应商的结算方式（字典 settle_method）、账期天数 */
    private String partnerSettleMethod;
    private Integer partnerCreditDays;
    private List<StatementItemVO> items;
    /** 和对方对账单的比对结果；没比对过、明细变了以后是空 */
    private CompareResult compare;
    private Long sentBy;
    private String sentByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime sentTime;
    /** 确认人、确认时间、确认时写的一句 */
    private Long auditBy;
    private String auditByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime auditTime;
    private String confirmNote;
    private String voidReason;
    /**
     * 现在能做的：refresh 刷新、removeItems 移出、addDeduction 加扣款、compare 上传对方对账单、notes 写差异说明、
     * send 发送、withdraw 撤回、confirm 确认、void 作废（按状态和权限算好）
     */
    private List<String> actions;
}
