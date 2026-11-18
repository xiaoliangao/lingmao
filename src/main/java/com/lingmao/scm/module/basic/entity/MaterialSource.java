package com.lingmao.scm.module.basic.entity;

import com.lingmao.scm.config.TradeFeature;
import lombok.Getter;

/** 物料的默认来源（bas_material.default_source 存名字）：加进资料包物料清单时带出（PRD §6.1.5） */
@Getter
public enum MaterialSource {
    PURCHASE("自购"),
    FACTORY("厂购"),
    /** 客供：贸易代工用，TRADE 关时不收（TradeFeature） */
    CUSTOMER_SUPPLIED("客供");

    private final String label;

    MaterialSource(String label) {
        this.label = label;
    }

    /** 是不是代工专用的值：名字和 TradeFeature 里的保留值是同一个 */
    public boolean isTrade() {
        return name().equals(TradeFeature.CUSTOMER_SUPPLIED);
    }
}
