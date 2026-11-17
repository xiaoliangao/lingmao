package com.lingmao.scm.module.production.dto;

import lombok.Data;

import java.util.List;

/** 看板按工厂的一行：在制 = 已下单 + 生产中的单数、件数（件数 − 已回货），和 02 审核对话框的在手负荷同一个口径 */
@Data
public class BoardFactoryRow {
    private Long factoryId;
    private String factoryShortName;
    private List<String> categoryNames = List.of();
    private long wipCount;
    private long wipQty;
    private long lateCount;
}
