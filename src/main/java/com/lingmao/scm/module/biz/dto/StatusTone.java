package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 状态标签的颜色（前端 components/StatusTag.vue 的 tone，types/biz.ts 的 StatusTone）。JSON 里是小写：active、pending……
 * DocRef 带着它，"下游单据"页签、1005 的清单、首页待办不用再按单据类型查一遍颜色
 */
public enum StatusTone {
    /** 定了：已审核、合作中 */
    ACTIVE,
    /** 进行中：执行中、部分到货、生产中 */
    PROGRESS,
    /** 等人处理：待审核 */
    PENDING,
    /** 完成：已完成、已到齐 */
    DONE,
    /** 草稿、停用 */
    OFF,
    /** 已结案 */
    CLOSED,
    /** 已作废、已取消 */
    VOID,
    /** 要紧：逾期、黑名单 */
    DANGER;

    @JsonValue
    public String json() {
        return name().toLowerCase(Locale.ROOT);
    }
}
