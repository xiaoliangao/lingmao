package com.lingmao.scm.module.fg.entity;

import lombok.Getter;

/**
 * 成品单据的类型（RFC 008 §3.1，前端 types/fg.ts 的 FG_DOC_TYPE，两边一起改）。
 * 过账时库存怎么动只看 kind：入库单入仓加、出库单出仓减
 */
@Getter
public enum FgDocType {
    RECEIPT_IN("回货入库", FgDocKind.IN, false, null),
    OTHER_IN("其他入库", FgDocKind.IN, true, "fg_in_reason"),
    SHIP_OUT("发货出库", FgDocKind.OUT, true, null),
    OTHER_OUT("其他出库", FgDocKind.OUT, true, "fg_out_reason");

    private final String label;
    private final FgDocKind kind;
    /** 能在页头手工新建；回货入库只能从"待入库"生成 */
    private final boolean manual;
    /** 原因用哪个字典（必填）；null = 没有原因 */
    private final String reasonDict;

    FgDocType(String label, FgDocKind kind, boolean manual, String reasonDict) {
        this.label = label;
        this.kind = kind;
        this.manual = manual;
        this.reasonDict = reasonDict;
    }
}
