package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.techpack.dto.PackOwner;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomItemSize;
import com.lingmao.scm.module.techpack.entity.BomItemSku;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.BomUsageMode;
import com.lingmao.scm.module.techpack.service.impl.PackContent.BomRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 资料包单元测试共用的小数据：款式两个颜色（1 卡其、2 黑）、S/M/L 基码 M，吊牌价 299 */
final class PackFixtures {

    static final long KHAKI = 1L;
    static final long BLACK = 2L;

    private PackFixtures() {
    }

    static PackOwner owner(String lockedReason) {
        return new PackOwner("FS001", "风衣", null, 2026, "AUTUMN", null, 1L, List.of(KHAKI, BLACK), new BigDecimal("299"), 30L,
                "风衣", "100% 棉", lockedReason);
    }

    static PackOwners.Ctx ctx() {
        return new PackOwners.Ctx(owner(null), List.of("S", "M", "L"), "M",
                List.of(new ColorOption(KHAKI, "KQ", "卡其", null), new ColorOption(BLACK, "BK", "黑色", null)));
    }

    static BomItem item(long materialId, BomSkuMode skuMode, String usage, String lossPct, String price, MaterialSource source) {
        BomItem item = new BomItem();
        item.setId(materialId * 10);
        item.setPart("大身");
        item.setMaterialId(materialId);
        item.setSkuMode(skuMode);
        item.setUsageMode(usage == null ? BomUsageMode.BY_SIZE : BomUsageMode.UNIFORM);
        item.setUsageQty(usage == null ? null : new BigDecimal(usage));
        item.setLossPct(new BigDecimal(lossPct));
        item.setRefPrice(price == null ? null : new BigDecimal(price));
        item.setSource(source);
        item.setPriceTaxIncluded(true);
        item.setPriceFreightIncluded(true);
        return item;
    }

    static BomItemSku sku(Long colorId, String size, long skuId, String price) {
        BomItemSku sku = new BomItemSku();
        sku.setColorId(colorId);
        sku.setSizeCode(size);
        sku.setMaterialSkuId(skuId);
        sku.setRefPrice(price == null ? null : new BigDecimal(price));
        return sku;
    }

    static BomItemSize size(String size, String qty) {
        BomItemSize row = new BomItemSize();
        row.setSizeCode(size);
        row.setUsageQty(new BigDecimal(qty));
        return row;
    }

    static BomRow row(BomItem item, List<BomItemSku> skus, List<BomItemSize> sizes) {
        return new BomRow(item, new ArrayList<>(skus), new ArrayList<>(sizes));
    }

    /** 启用的物料：库存单位米，采购单位给定、换算系数给定 */
    static MaterialRef material(long id, String type, String refPrice, String factor, Long defaultSupplier) {
        return new MaterialRef(id, "WL" + id, "物料" + id, type, "METER", "KG", new BigDecimal(factor),
                refPrice == null ? null : new BigDecimal(refPrice), "PURCHASE", new BigDecimal("2"), null, defaultSupplier, 1);
    }
}
