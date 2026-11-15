package com.lingmao.scm.common.doc;

import lombok.Getter;

/**
 * 单据的通用状态（架构 §4.2、PRD §5.1）。库里存英文名（VARCHAR(16)），页面按单据类型显示中文：
 * 采购单的 RUNNING 显示"部分到货"，需求订单的 VOIDED 显示"已取消"，所以这里的中文只是默认的叫法。
 * <p>
 * 各单据只用其中一部分，用哪些、怎么转，写在各自的 StatusFlow 里（物料入库单审核直接 DRAFT → APPROVED）。
 * 资料包、算料单、供应商、款式、异常、合同的状态不一样，各用自己的枚举，同样配 StatusFlow、同样比较并交换（StatusCas）。
 * 为什么不用 PostgreSQL 的 ENUM 或 CHECK：以后加状态要改类型、删约束，和"迁移只增不改"冲突，合法性由这里和转换表保证。
 */
@Getter
public enum DocStatus {
    DRAFT("草稿"),
    PENDING("待审核"),
    APPROVED("已审核"),
    RUNNING("执行中"),
    DONE("已完成"),
    CLOSED("已结案"),
    VOIDED("已作废");

    private final String label;

    DocStatus(String label) {
        this.label = label;
    }
}
