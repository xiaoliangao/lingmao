package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.util.List;

/**
 * 物料详情（GET /api/materials/{id}）：物料自己的列（含领猫附加属性 legacyAttrs）、分类和默认供应商的名字、
 * 全部 SKU、图片（第一张是主图）
 */
public record MaterialDetailVO(Material material, String categoryCode, String categoryName, String parentCategoryName,
                               String defaultSupplierName, List<MaterialSkuVO> skus, List<AttachmentVO> images) {
}
