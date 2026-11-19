package com.lingmao.scm.module.fg.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.util.List;
import java.util.Map;

/** 按款汇总一行（前端 FgStockByStyleRow）：画矩阵的数据 + 各 SKU 的件数（几个仓加在一起） */
public record FgStockByStyleRowVO(Long styleId, String code, String name, AttachmentVO image, List<String> sizes,
                                  List<FgStyleOptionVO.Color> colors, List<FgStyleOptionVO.Sku> skus, int totalQty,
                                  Map<String, Integer> qtys) {
}
