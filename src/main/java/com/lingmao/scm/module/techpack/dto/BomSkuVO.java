package com.lingmao.scm.module.techpack.dto;

import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.math.BigDecimal;

/** BOM 行的一条配料（前端 BomSku） */
@Data
public class BomSkuVO {
    /** 款色；固定、随尺码的是 null */
    private Long colorId;
    private String colorCode;
    private String colorName;
    private String colorHex;
    /** 尺码；固定、随款色的是 null */
    private String sizeCode;
    private Long materialSkuId;
    private String skuCode;
    /** 物料 SKU 自己的颜色、规格 */
    private String skuColorName;
    private String skuSpec;
    /** 物料 SKU 停用了（0）：页面标红，生效时会被拦 */
    private Integer skuStatus;
    /** 这个 SKU 单独的参考单价，元 / 库存单位；null = 用行上的 */
    private BigDecimal refPrice;
    /** SKU 图，没有用物料主图 */
    private AttachmentVO image;
}
