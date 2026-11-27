package com.lingmao.scm.module.settle.dto;

import lombok.Data;

/** 待对账 */
@Data
public class PendingStatementQuery {
    /** 主体 */
    private Long companyId;
    /** 供应商或加工厂 */
    private Long partnerId;
}
