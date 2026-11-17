package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.pdf.PdfRenderer;
import com.lingmao.scm.module.basic.dto.MaterialSkuRef;
import com.lingmao.scm.module.basic.entity.MaterialSource;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.techpack.dto.CostRowVO;
import com.lingmao.scm.module.techpack.dto.CostSummary;
import com.lingmao.scm.module.techpack.dto.TechPackCostVO;
import com.lingmao.scm.module.techpack.entity.BomItem;
import com.lingmao.scm.module.techpack.entity.BomSkuMode;
import com.lingmao.scm.module.techpack.entity.CraftSection;
import com.lingmao.scm.module.techpack.entity.CraftText;
import com.lingmao.scm.module.techpack.entity.PackProcess;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import com.lingmao.scm.module.techpack.entity.TechPack;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import com.lingmao.scm.module.techpack.service.impl.PackContent.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.KHAKI;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.ctx;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.item;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.material;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.row;
import static com.lingmao.scm.module.techpack.service.impl.PackFixtures.sku;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 导出 PDF 的工艺单（架构 §11.1）：选项在拼数据时套进去，模板拿到的就是要印的 */
@ExtendWith(MockitoExtension.class)
class TechPackPdfServiceImplTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private TechPackMapper techPackMapper;
    @Mock
    private PackOwners packOwners;
    @Mock
    private PackContent packContent;
    @Mock
    private CostCalculator costCalculator;
    @Mock
    private CraftStandardService craftStandardService;
    @Mock
    private MaterialService materialService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private FileService fileService;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private PdfRenderer pdfRenderer;

    private TechPackPdfServiceImpl service;
    private TechPack pack;

    @BeforeEach
    void setUp() {
        service = new TechPackPdfServiceImpl(techPackMapper, packOwners, packContent, costCalculator, craftStandardService,
                materialService, attachmentService, fileService, dictDataService, docLogService, pdfRenderer, CLOCK);
        pack = new TechPack();
        pack.setId(7L);
        pack.setStyleId(1L);
        pack.setStage(PackStage.PROD);
        pack.setVersion(2);
        pack.setStatus(PackStatus.EFFECTIVE);
        pack.setPublishTime(OffsetDateTime.parse("2026-11-10T09:30:00+08:00"));
    }

    /** 一行按款色配料的面料（只配了卡其）、只写了缝制要求、一道工序；没有尺寸指示 */
    private void stubContent() {
        // 一行按款色配料的面料、只写了缝制要求、一道工序；没有尺寸指示
        BomItem fabric = item(1L, BomSkuMode.BY_COLOR, "1.5", "3", "18.5", MaterialSource.PURCHASE);
        fabric.setSeq(1);
        fabric.setUnit("METER");
        CraftText sewing = new CraftText();
        sewing.setSection(CraftSection.SEWING);
        sewing.setContent("针距 3cm 12 针");
        PackProcess process = new PackProcess();
        process.setSeq(1);
        process.setName("合肩缝");
        process.setStdSeconds(40);
        process.setPrice(new BigDecimal("1.2"));
        when(packContent.load(7L)).thenReturn(new Content(List.of(),
                List.of(row(fabric, List.of(sku(KHAKI, null, 11L, null)), List.of())),
                List.of(sewing), List.of(new PackContent.ProcessRow(process, List.of())), List.of()));
        when(materialService.materialRefs(any())).thenReturn(List.of(material(1L, "FABRIC", "18.5", "1", null)));
        when(materialService.skuRefs(any())).thenReturn(List.of(new MaterialSkuRef(11L, "WL1-KQ", null, KHAKI, "KQ", "卡其",
                "150cm", null, null, null, 1)));
        when(dictDataService.listByType("unit")).thenReturn(List.of(new DictOption("米", "METER", 1)));
        when(dictDataService.listByType("season")).thenReturn(List.of(new DictOption("秋", "AUTUMN", 1)));
    }

    /** 规则（架构 §11.1）：不含价格时，拼出来的数据里就没有单价、单件成本、核价和工序工价，也不去算成本；没填的尺寸指示、没写的后道和注意事项不出 */
    @Test
    void sheet_withoutPrice_hasNoPriceAndOmitsEmptySections() {
        stubContent();
        PdfSheet sheet = service.sheet(pack, ctx(), ctx().colors(), false);

        assertThat(sheet.getTitle()).isEqualTo("2026秋季工艺单");
        assertThat(sheet.getStatusMark()).isNull();
        assertThat(sheet.getMeta().getDate()).isEqualTo("2026-11-10");
        assertThat(sheet.isPrice()).isFalse();
        assertThat(sheet.getCost()).isNull();
        assertThat(sheet.getBoms()).hasSize(2);
        assertThat(sheet.getBoms()).flatExtracting(PdfSheet.BomTable::getRows)
                .allSatisfy(r -> assertThat(r.getPrice()).isNull())
                .allSatisfy(r -> assertThat(r.getCost()).isNull());
        PdfSheet.BomRow khaki = sheet.getBoms().getFirst().getRows().getFirst();
        assertThat(khaki.getSkuCodes()).isEqualTo("WL1-KQ");
        assertThat(khaki.getMaterialName()).isEqualTo("物料1-卡其 150cm");
        assertThat(sheet.getBoms().get(1).getRows().getFirst().getSkuCodes()).isEqualTo("未配料");
        assertThat(sheet.getFabricUsage()).isEqualTo("大身 物料1 1.5 米/件（M 码）");

        assertThat(sheet.getSpec()).isNull();
        assertThat(sheet.getCraftGroups()).singleElement().satisfies(g -> {
            assertThat(g.getName()).isEqualTo("车间缝制");
            assertThat(g.getItems()).extracting(PdfSheet.CraftItem::getLabel).containsExactly("缝制要求");
            assertThat(g.getProcesses()).isEmpty();
        });
        verify(costCalculator, never()).compute(any(), any(), anyLong(), anyBoolean());
    }

    /** 规则：含价格时行上有单价和单件成本，核价按第一个颜色算，工序工价放在车间缝制下面；草稿印"草稿"、日期是今天 */
    @Test
    void sheet_withPrice_showsCostAndProcesses() {
        stubContent();
        pack.setStatus(PackStatus.DRAFT);
        TechPackCostVO cost = new TechPackCostVO();
        CostRowVO costRow = new CostRowVO();
        costRow.setBomItemId(10L);
        costRow.setPrice(new BigDecimal("18.5"));
        costRow.setCounted(true);
        costRow.setCost(new BigDecimal("28.58"));
        cost.setFabricRows(List.of(costRow));
        cost.setTrimRows(List.of());
        cost.setSummary(new CostSummary(new BigDecimal("28.58"), BigDecimal.ZERO, new BigDecimal("1.2"), BigDecimal.ZERO,
                new BigDecimal("29.78")));
        when(costCalculator.compute(eq(pack), any(), eq(KHAKI), eq(false))).thenReturn(cost);

        PdfSheet sheet = service.sheet(pack, ctx(), ctx().colors().subList(0, 1), true);

        assertThat(sheet.getStatusMark()).isEqualTo("草稿");
        assertThat(sheet.getMeta().getDate()).isEqualTo("2026-11-17");
        assertThat(sheet.getBoms()).singleElement().satisfies(b -> {
            assertThat(b.getColorName()).isEqualTo("卡其");
            assertThat(b.getRows().getFirst().getPrice()).isEqualTo("18.50/米");
            assertThat(b.getRows().getFirst().getCost()).isEqualTo("28.58");
        });
        assertThat(sheet.getCost().getBasis()).isEqualTo("按 卡其、M 码用量");
        assertThat(sheet.getCost().getTotal()).isEqualTo("29.78");
        assertThat(sheet.getCraftGroups().getFirst().getProcesses()).extracting(PdfSheet.ProcessRow::getName).containsExactly("合肩缝");
        assertThat(sheet.getCraftGroups().getFirst().getLaborTotal()).isEqualTo("1.20");
    }

    /** 规则：只出一个颜色时，颜色要是款式现在启用的；不是 → 1000，不生成、不记操作记录 */
    @Test
    void export_rejectsColorNotOnStyle() {
        when(techPackMapper.selectById(7L)).thenReturn(pack);
        when(packOwners.of(pack)).thenReturn(ctx().owner());
        when(packOwners.context(ctx().owner())).thenReturn(ctx());

        assertThatThrownBy(() -> service.export(7L, 99L, false, true))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是款式现在启用的颜色");
        verify(pdfRenderer, never()).render(any(), any());
        verify(docLogService, never()).add(any(), anyLong(), any(), any());
    }
}
