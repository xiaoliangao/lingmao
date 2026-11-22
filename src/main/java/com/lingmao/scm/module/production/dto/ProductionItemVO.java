package com.lingmao.scm.module.production.dto;

import lombok.Data;

/** 生产单明细一行 = 一个款式 SKU（前端 ProductionItem） */
@Data
public class ProductionItemVO {
    private Long id;
    private Integer seq;
    private Long orderItemId;
    private Long styleSkuId;
    /** 款号-颜色码-尺码 */
    private String skuCode;
    private Long colorId;
    private String colorCode;
    private String colorName;
    private String colorHex;
    private String sizeCode;
    private int qty;
    /** 已回货，含次品；良品 = returnedQty − defectQty（上限、回齐、未回都按良品，甲方 11-19） */
    private int returnedQty;
    /** 其中次品（退回工厂返修，修好再登记一次回货） */
    private int defectQty;
    /** 裁床数（RFC 013 §4）：null = 没登记 */
    private Integer cutQty;
    /** 未回 = 件数（登记了裁床数按裁床数）− 已回良品，不小于 0 */
    private int remainQty;
    /** 良品最多还能回 = ⌊件数或裁床数 × 1.05⌋ − 已回良品，不小于 0（Q09）；次品不占 */
    private int maxReceiveQty;
    /** 登记裁床数的范围：最多 ⌊件数 × 1.05⌋，最少让已回良品不超出新上限 */
    private int maxCutQty;
    private int minCutQty;
}
