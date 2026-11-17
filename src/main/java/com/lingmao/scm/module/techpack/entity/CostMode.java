package com.lingmao.scm.module.techpack.entity;

import lombok.Getter;

/** 核价按什么算（tp_tech_pack.cost_mode，PRD §6.4.5）：按外发 = 加工费取工序工价合计；按成品采购 = 加工费取成品单价（含料） */
@Getter
public enum CostMode {
    OUTSOURCE("按外发算"),
    BUY("按成品采购算");

    private final String label;

    CostMode(String label) {
        this.label = label;
    }
}
