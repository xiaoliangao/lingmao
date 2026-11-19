package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.MaterialRef;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.techpack.dto.BomDefaultVO;
import com.lingmao.scm.module.techpack.dto.BomImportVO;
import com.lingmao.scm.module.techpack.dto.BomItemVO;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.service.impl.BomSheetReader.SheetData;
import com.lingmao.scm.security.FieldPermissions;
import com.lingmao.scm.security.LoginUser;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.lingmao.scm.module.techpack.service.impl.BomSheetReaderTest.cells;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.KHAKI;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.ctx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomImporterTest {

    @Mock
    private MaterialService materialService;
    @Mock
    private SupplierService supplierService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private BomPricing bomPricing;

    private BomImporter importer;

    @BeforeEach
    void setUp() {
        importer = new BomImporter(materialService, supplierService, dictDataService, bomPricing);
    }

    /** 字典、供应商、人工挑的物料：读表的两个用例共用 */
    private void lookups() {
        when(dictDataService.listByType("unit")).thenReturn(List.of(new DictOption("米", "METER", 1), new DictOption("厘米", "CM", 1),
                new DictOption("条", "STRIP", 1)));
        when(dictDataService.listByType("bom_part")).thenReturn(List.of(new DictOption("大身", "BODY", 1),
                new DictOption("拉链", "ZIPPER", 1), new DictOption("织带", "WEBBING", 0)));
        when(supplierService.refsByNames(anyCollection())).thenReturn(Map.of());
        when(materialService.enabledSkusOf(anyCollection())).thenReturn(Map.of());
    }

    @AfterEach
    void logout() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 规则（RFC 011 §4.2）：表里按厘米写、物料按米管的，用量 ÷ 100、单价 × 100，标 CHECK 让人看一眼；
     * 写了一个颜色 → 固定配料，配这个颜色的 SKU（"卡其色"和"卡其"算一样）；部位按名称末尾的部位字（"YKK 拉链" → 拉链）；
     * 损耗这一列不是系数，3 就是 3%
     */
    @Test
    void cmConvertedAndSingleColorFixed() throws Exception {
        login(true);
        lookups();
        MaterialRef zipper = material(5L, "YKK 拉链");
        when(materialService.enabledSkusByNames(anyCollection())).thenReturn(Map.of("ykk 拉链", List.of(
                sku(51L, zipper, KHAKI, "卡其", null), sku(52L, zipper, 2L, "黑色", null))));
        when(bomPricing.defaults(anyList())).thenReturn(List.of(defaults(5L)));

        BomImportVO vo = parse(row -> cells(row, "辅料", "辅料", "YKK 拉链", "", "卡其色", 0.2, "厘米", 120, 3, 24, "", ""));

        BomImportVO.Row row = vo.rows().getFirst();
        assertThat(row.status()).isEqualTo(BomImporter.CHECK);
        assertThat(row.notes()).anySatisfy(n -> assertThat(n).contains("换算过了"));
        BomItemVO item = row.item();
        assertThat(item.getUsageQty()).isEqualByComparingTo("1.2");
        assertThat(item.getRefPrice()).isEqualByComparingTo("20");
        assertThat(item.getLossPct()).isEqualByComparingTo("3");
        assertThat(item.getPart()).isEqualTo("ZIPPER");
        assertThat(item.getSkuMode()).isEqualTo(BomSkuMode.FIXED);
        assertThat(item.getSkus()).singleElement().satisfies(s -> assertThat(s.getMaterialSkuId()).isEqualTo(51L));
    }

    /**
     * 规则：物料库里同名的有两个，用规格对 SKU 的规格挑出一个；对不上的行 UNMATCHED、item 是 null。
     * 没有看成本权限：单价、加工费、运费都不出，表里的单价原文也不出
     */
    @Test
    void sameNameBySpec_andNoCostHidesPrices() throws Exception {
        login(false);
        lookups();
        MaterialRef narrow = material(6L, "罗纹");
        MaterialRef wide = material(7L, "罗纹");
        when(materialService.enabledSkusByNames(anyCollection())).thenReturn(Map.of("罗纹", List.of(
                sku(61L, narrow, null, null, "2cm"), sku(71L, wide, null, null, "5cm"))));
        when(bomPricing.defaults(anyList())).thenReturn(List.of(defaults(7L)));

        BomImportVO vo = parse(
                row -> cells(row, "辅料", "辅料", "罗纹", "5cm", "/", 3, "米", 0.3, 3, 0.9, "", ""),
                row -> cells(row, "辅料", "辅料", "没有的料", "", "", 1, "米", 1, null, 1, "", ""),
                row -> cells(row, "加工费", "", "", "", "", null, "", null, null, 25.5, "", ""));

        assertThat(vo.rows().getFirst().item().getMaterialId()).isEqualTo(7L);
        assertThat(vo.rows().getFirst().item().getRefPrice()).isNull();
        assertThat(vo.rows().getFirst().source().price()).isNull();
        assertThat(vo.rows().get(1).status()).isEqualTo(BomImporter.UNMATCHED);
        assertThat(vo.rows().get(1).item()).isNull();
        assertThat(vo.laborPrice()).isNull();
        assertThat(vo.fees()).isEmpty();
    }

    /** 规则：没指定 sheet 时先按第 1 行的款号对，再按款名、sheet 名，都对不上用第一张能认的；指定了不是 BOM 的、一张都认不出 → 1000 */
    @Test
    void chooseSheet() {
        SheetData other = sheet(0, "X1", "别的款", true);
        SheetData byName = sheet(1, null, "风衣", true);
        SheetData byCode = sheet(2, "fs001", "随便", true);
        SheetData notes = sheet(3, null, null, false);
        var owner = PackFixtures.owner(null);

        assertThat(BomImporter.choose(List.of(other, byName, byCode), owner, null)).isSameAs(byCode);
        assertThat(BomImporter.choose(List.of(other, byName), owner, null)).isSameAs(byName);
        assertThat(BomImporter.choose(List.of(notes, other), owner, null)).isSameAs(other);
        assertThatThrownBy(() -> BomImporter.choose(List.of(other, notes), owner, 1))
                .isInstanceOf(BizException.class).hasMessageContaining("不是物料清单");
        assertThatThrownBy(() -> BomImporter.choose(List.of(notes), owner, null))
                .isInstanceOf(BizException.class).hasMessageContaining("认不出这张表");
    }

    // ---------------------------------------------------------------- 数据

    private BomImportVO parse(java.util.function.Consumer<org.apache.poi.ss.usermodel.Row>... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("FS001");
            cells(sheet.createRow(0), "FS001 风衣 BOM");
            cells(sheet.createRow(1), "项目", "", "物料名称", "规格", "颜色", "单价", "单位", "单件用量", "损耗", "预估价", "工厂", "备注");
            for (int i = 0; i < rows.length; i++) {
                rows[i].accept(sheet.createRow(i + 2));
            }
            return importer.parse(wb, ctx(), null, Map.of());
        }
    }

    private static void login(boolean cost) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(1L, "t"), null,
                cost ? List.of(new SimpleGrantedAuthority(FieldPermissions.COST)) : List.of()));
    }

    private static MaterialRef material(long id, String name) {
        return new MaterialRef(id, "WL" + id, name, "TRIM", "METER", "METER", BigDecimal.ONE, new BigDecimal("1"), "PURCHASE",
                new BigDecimal("2"), null, null, 1);
    }

    private static MaterialSkuRef sku(long id, MaterialRef material, Long colorId, String colorName, String spec) {
        return new MaterialSkuRef(id, material.code() + "-" + id, material, colorId, null, colorName, spec, null,
                BigDecimal.ONE, null, 1);
    }

    private static BomDefaultVO defaults(long materialId) {
        BomDefaultVO d = new BomDefaultVO();
        d.setMaterialId(materialId);
        d.setUnit("METER");
        d.setLossPct(new BigDecimal("2"));
        d.setSource(MaterialSource.PURCHASE);
        d.setRefPrice(BigDecimal.ONE);
        d.setPriceTaxIncluded(true);
        d.setPriceFreightIncluded(true);
        d.setPriceFrom(BomPricing.FROM_SKU);
        return d;
    }

    private static SheetData sheet(int index, String code, String name, boolean bom) {
        return new SheetData(index, "S" + index, null, code, name, bom, List.of(), null, List.of());
    }
}
