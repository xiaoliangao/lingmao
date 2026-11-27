package com.lingmao.scm.module.system.dto;

/** 推到钉钉的消息种类（甲方 11-27 答 Q65 选了前两种，RFC 019 §4） */
public enum NotifyEvent {
    /** 单据被驳回：发给提交的人 */
    DOC_REJECTED("单据被驳回", "需求订单、采购单、生产单、变更单、供应商准入等被驳回时，发给提交的人"),
    /** 货期预警：每天 8:30 一条，发给生产单的跟单员、采购单的采购员 */
    DUE_WARNING("货期预警", "每天早上 8:30 一条：跟的生产单逾期、7 天内到期、节点延期，采购单到料逾期");

    private final String label;
    private final String description;

    NotifyEvent(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
