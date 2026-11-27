package com.lingmao.scm.module.quality.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 检验结论。产前样只有合格（通过）、不合格（不通过） */
@Getter
@RequiredArgsConstructor
public enum QcResult {
    PASS("合格"),
    CONCESSION("让步接收"),
    FAIL("不合格");

    private final String label;
}
