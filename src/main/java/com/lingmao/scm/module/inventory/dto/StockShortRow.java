package com.lingmao.scm.module.inventory.dto;

import java.math.BigDecimal;

/**
 * 1001 库存不足的一行（docs/api/04 §3.4.1，前端 StockShortRow）：全局的错误弹框按字段顺序直接画表，名称、单位是翻好的中文
 *
 * @param seqs      单上第几行，同仓同 SKU 的几行合成一行时"3、5"
 * @param stockQty  现在的库存
 * @param needQty   这次要扣的（盘点是盘亏的数）
 * @param shortQty  差多少
 */
public record StockShortRow(String seqs, String warehouseName, String skuCode, String materialName,
                            BigDecimal stockQty, BigDecimal needQty, BigDecimal shortQty, String unit) {
}
