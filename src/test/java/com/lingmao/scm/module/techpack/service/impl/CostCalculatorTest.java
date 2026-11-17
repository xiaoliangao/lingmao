package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.CostItem;
import com.lingmao.scm.module.techpack.entity.CostMode;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.BLACK;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.KHAKI;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.ctx;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.item;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.material;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.row;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostCalculatorTest {

    @Mock
    private TechPackMapper techPackMapper;
    @Mock
    private PackContent packContent;
    @Mock
    private MaterialService materialService;
    @Mock
    private PriceService priceService;
    @Mock
    private SupplierService supplierService;

    private CostCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new CostCalculator(techPackMapper, packContent, materialService, priceService, supplierService,
                Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai")));
        when(supplierService.refs(anyCollection())).thenReturn(Map.of());
    }

    /**
     * 规则（PRD §6.4.5）：面料块 = 面料、里料的行，辅料块 = 其余；只算我方采购的行，工厂自备的不计；
     * 一行 = 基码用量 ×（1 + 损耗%）× 这个颜色配到的 SKU 单价（没有用行上的）；合计 = 四块相加，倍率 = 吊牌价 ÷ 合计
     */
    @Test
    void outsource_splitsBlocksAndCountsPurchaseOnly() {
        TechPack pack = pack(CostMode.OUTSOURCE, null);
        // 面料随款色：卡其单独 20 元，黑色用行上的 18；用量 1.5、损耗 4%
        var fabric = row(item(1, BomSkuMode.BY_COLOR, "1.5", "4", "18", MaterialSource.PURCHASE),
                List.of(sku(KHAKI, null, 11, "20"), sku(BLACK, null, 12, null)), List.of());
        var button = row(item(2, BomSkuMode.FIXED, "6", "0", "0.35", MaterialSource.PURCHASE), List.of(), List.of());
        var thread = row(item(3, BomSkuMode.FIXED, "1", "0", "6", MaterialSource.FACTORY), List.of(), List.of());
        when(packContent.loadBom(9L)).thenReturn(List.of(fabric, button, thread));
        when(packContent.loadProcesses(9L)).thenReturn(List.of(process("12.5"), process("20")));
        when(packContent.loadCostItems(9L)).thenReturn(List.of(costItem("4")));
        when(materialService.materialRefs(anyCollection())).thenReturn(List.of(material(1, "FABRIC", "18", "1", null),
                material(2, "TRIM", "0.35", "1", null), material(3, "TRIM", "6", "1", null)));
        when(materialService.skuRefs(anyCollection())).thenReturn(List.of());

        TechPackCostVO khaki = calculator.compute(pack, ctx(), KHAKI, false);
        assertThat(khaki.getFabricRows()).singleElement().satisfies(r -> assertThat(r.getCost()).isEqualByComparingTo("31.20"));
        assertThat(khaki.getTrimRows()).extracting(r -> r.getCost().toPlainString()).containsExactly("2.10", "0.00");
        assertThat(khaki.getTrimRows().get(1).isCounted()).isFalse();
        assertThat(khaki.getSummary().labor()).isEqualByComparingTo("32.50");
        assertThat(khaki.getSummary().total()).isEqualByComparingTo("69.80");   // 31.20 + 2.10 + 32.50 + 4
        assertThat(khaki.getRatio()).isEqualByComparingTo("4.28");               // 299 / 69.80

        TechPackCostVO black = calculator.compute(pack, ctx(), BLACK, false);
        assertThat(black.getSummary().fabric()).isEqualByComparingTo("28.08");  // 1.5 × 1.04 × 18
    }

    /** 规则：按成品采购算，加工费 = 成品单价；没填用默认加工厂"包工包料"的现价 */
    @Test
    void buy_usesBuyPriceElseArchiveDefault() {
        when(packContent.loadBom(9L)).thenReturn(List.of());
        when(packContent.loadProcesses(9L)).thenReturn(List.of(process("30")));
        when(packContent.loadCostItems(9L)).thenReturn(List.of());
        when(priceService.currentProcess(30L, 100L, CostCalculator.BUY_PROCESS_MODE, LocalDate.of(2026, 11, 17)))
                .thenReturn(Optional.of(new PriceRef(77L, new BigDecimal("85"), "PIECE", BigDecimal.TEN, true, true,
                        LocalDate.of(2026, 1, 1), null)));

        TechPackCostVO byDefault = calculator.compute(pack(CostMode.BUY, null), ctx(), KHAKI, false);
        assertThat(byDefault.getBuyPriceDefault()).isEqualByComparingTo("85");
        assertThat(byDefault.getSummary().labor()).isEqualByComparingTo("85");
        assertThat(byDefault.getLaborTotal()).isEqualByComparingTo("30");

        TechPackCostVO typed = calculator.compute(pack(CostMode.BUY, new BigDecimal("90")), ctx(), KHAKI, false);
        assertThat(typed.getSummary().total()).isEqualByComparingTo("90");
    }

    private static TechPack pack(CostMode mode, BigDecimal buyPrice) {
        TechPack pack = new TechPack();
        pack.setId(9L);
        pack.setStyleId(100L);
        pack.setStage(PackStage.PROD);
        pack.setCostMode(mode);
        pack.setBuyPrice(buyPrice);
        return pack;
    }

    private static PackContent.ProcessRow process(String price) {
        PackProcess p = new PackProcess();
        p.setName("工序");
        p.setPrice(new BigDecimal(price));
        return new PackContent.ProcessRow(p, List.of());
    }

    private static CostItem costItem(String amount) {
        CostItem item = new CostItem();
        item.setName("包装");
        item.setAmount(new BigDecimal(amount));
        return item;
    }
}
