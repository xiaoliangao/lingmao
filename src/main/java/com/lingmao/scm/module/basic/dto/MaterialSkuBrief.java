package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

/**
 * 一个物料 SKU 的显示字段（前端 types/inventory.ts 的 MaterialSkuBrief）：入库出库明细、盘点明细、库存、库存日志，
 * 以后采购明细、算料行也带这一组。别的域的行 VO 继承它，用 {@link #fillFrom} 填：
 * <pre>
 * Map&lt;Long, MaterialSkuBrief&gt; briefs = materialService.skuBriefs(skuIds);
 * vo.fillFrom(briefs.get(item.getMaterialSkuId()));
 * </pre>
 * 名称、颜色、图是查询时现取的，物料改了名旧单上跟着变；单价、换算系数这些"当时按什么算的"存在各单据行上
 */
@Data
public class MaterialSkuBrief {

    private Long materialSkuId;
    /** 物料编码-颜色码[-规格码] */
    private String skuCode;
    private Long materialId;
    private String materialCode;
    private String materialName;
    /** 字典 material_type */
    private String materialType;
    /** 无色物料是 null */
    private String colorName;
    private String colorHex;
    private String spec;
    /** 库存单位，字典 unit 的 value */
    private String stockUnit;
    /** SKU 图，没有用物料主图，都没有是 null */
    private AttachmentVO image;

    /** 把 brief 的字段抄到自己身上；brief 是 null 时不动 */
    public void fillFrom(MaterialSkuBrief brief) {
        if (brief == null) {
            return;
        }
        materialSkuId = brief.materialSkuId;
        skuCode = brief.skuCode;
        materialId = brief.materialId;
        materialCode = brief.materialCode;
        materialName = brief.materialName;
        materialType = brief.materialType;
        colorName = brief.colorName;
        colorHex = brief.colorHex;
        spec = brief.spec;
        stockUnit = brief.stockUnit;
        image = brief.image;
    }

    /** 给人看的全名："220g 斜纹棉-卡其 150cm"（1001、1004 的清单、导出用） */
    public String fullName() {
        return materialName + (colorName == null ? "" : "-" + colorName) + (spec == null || spec.isBlank() ? "" : " " + spec);
    }
}
