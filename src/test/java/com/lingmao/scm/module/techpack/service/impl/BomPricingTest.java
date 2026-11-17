package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.techpack.dto.BomDefaultReq;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.entity.PackStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.material;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomPricingTest {

    private static final long SUPPLIER = 7L;
    /** 采购单位公斤、1 公斤 = 4 米，参考价 40 元/公斤 */
    private static final MaterialRef FABRIC = material(1, "FABRIC", "40", "4", SUPPLIER);

    @Mock
    private MaterialService materialService;
    @Mock
    private SupplierService supplierService;
    @Mock
    private PriceService priceService;

    private BomPricing pricing;

    @BeforeEach
    void setUp() {
        pricing = new BomPricing(materialService, supplierService, priceService,
                Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        when(materialService.materialRefs(anyCollection())).thenReturn(List.of(FABRIC));
        when(supplierService.refs(anyCollection())).thenReturn(Map.of());
    }

    /**
     * 规则（PRD §6.4.3）：生产 BOM 取价——价格档案这个 SKU 的现价 &gt; 这个物料的现价 &gt; SKU 单价；供应商没给用物料的默认供应商；
     * 都换成元 / 库存单位（÷ 4）。行上取第一个 SKU 的，其他 SKU 价不一样的单独给
     */
    @Test
    void prod_archiveFirst_perStockUnit() {
        when(materialService.skuRefs(anyCollection())).thenReturn(List.of(sku(11, "44"), sku(12, "48")));
        when(priceService.currentMaterials(anyCollection(), any())).thenReturn(Map.of(
                new MaterialPriceKey(SUPPLIER, 1, 11L), archive(101, "50"),
                new MaterialPriceKey(SUPPLIER, 1, null), archive(102, "42")));

        BomDefaultVO vo = pricing.defaults(PackStage.PROD, List.of(req(11L, 12L))).getFirst();

        assertThat(vo.getSupplierId()).isEqualTo(SUPPLIER);
        assertThat(vo.getRefPrice()).isEqualByComparingTo("12.5");           // SKU 11 的档案价 50 ÷ 4
        assertThat(vo.getPriceFrom()).isEqualTo(BomPricing.FROM_ARCHIVE);
        assertThat(vo.getPriceSourceId()).isEqualTo(101L);
        assertThat(vo.getSkuPrices()).containsExactly(new BomDefaultVO.SkuPrice(12L, new BigDecimal("10.5000")));  // 物料档案价 42 ÷ 4
    }

    /** 规则：研发 BOM 不查价格档案；物料停用了 → 1000 */
    @Test
    void dev_skipsArchive_disabledMaterialRejected() {
        when(materialService.skuRefs(anyCollection())).thenReturn(List.of(sku(11, null)));

        BomDefaultVO vo = pricing.defaults(PackStage.DEV, List.of(req(11L))).getFirst();
        assertThat(vo.getRefPrice()).isEqualByComparingTo("10");             // 没有 SKU 价，用物料参考价 40 ÷ 4
        assertThat(vo.getPriceFrom()).isEqualTo(BomPricing.FROM_MATERIAL);
        verifyNoInteractions(priceService);

        MaterialRef disabled = new MaterialRef(1L, "WL1", "旧面料", "FABRIC", "METER", "KG", new BigDecimal("4"), null, null, null,
                null, null, 0);
        when(materialService.materialRefs(anyCollection())).thenReturn(List.of(disabled));
        assertThatThrownBy(() -> pricing.defaults(PackStage.DEV, List.of(req())))
                .isInstanceOf(BizException.class).hasMessageContaining("已经停用了");
    }

    private static BomDefaultReq req(Long... skuIds) {
        BomDefaultReq req = new BomDefaultReq();
        req.setMaterialId(1L);
        req.setSkuIds(List.of(skuIds));
        return req;
    }

    private static MaterialSkuRef sku(long id, String price) {
        BigDecimal p = price == null ? null : new BigDecimal(price);
        return new MaterialSkuRef(id, "WL1-" + id, FABRIC, null, null, null, null, null, p, p, 1);
    }

    private static PriceRef archive(long id, String price) {
        return new PriceRef(id, new BigDecimal(price), "KG", new BigDecimal("13"), true, false, LocalDate.of(2026, 1, 1), null);
    }
}
