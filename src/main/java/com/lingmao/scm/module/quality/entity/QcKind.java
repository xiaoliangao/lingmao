package com.lingmao.scm.module.quality.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 检验的种类（RFC 019 §6.1）：来料检验对采购入库单，尾期验货对回货单，产前样、中检对生产单 */
@Getter
@RequiredArgsConstructor
public enum QcKind {
    INCOMING("来料检验"),
    PRE_PRODUCTION("产前样确认"),
    MIDDLE("中检"),
    FINAL("尾期验货");

    private final String label;

    /** 对生产单记的（产前样、中检） */
    public boolean onOrder() {
        return this == PRE_PRODUCTION || this == MIDDLE;
    }
}
