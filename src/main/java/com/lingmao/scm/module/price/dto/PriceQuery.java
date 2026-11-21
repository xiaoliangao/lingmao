package com.lingmao.scm.module.price.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.price.entity.PriceType;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 价格列表的条件（GET /api/prices，RFC 006 §7）。三个详情页的"价格记录"页签各传自己的那一个 id。
 * <ul>
 *   <li>current = true（只看现价）：每组（供应商 × 物料 × SKU，或工厂 × 款式 × 加工方式）一行，口径同 PriceService 取现价：
 *       今天生效的最新一条，物料价只认物料现在的采购单位；这一组还没有生效的、只有以后才生效的，给最早生效的那条。
 *       作废的不算，includeVoided 不管用</li>
 *   <li>不传或 false：全部历史，生效日期新的在前；includeVoided = true 时作废的也给</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PriceQuery extends PageQuery {

    /** 价格类型，取值见枚举；不传是全部 */
    private PriceType type;

    private Long supplierId;

    private Long materialId;

    private Long materialSkuId;

    private Long styleId;

    /** 字典 process_mode 的 value */
    private String processMode;

    /** 只看现价 */
    private Boolean current;

    /** 含作废的（只看现价时不管用） */
    private Boolean includeVoided;
}
