package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.basic.mapper.MaterialSkuMapper;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 领猫两张表能导；表头不认识报错；分类对不上整批不导；颜色没有就建、进提示；客供改成我方采购、进提示（RFC 003 §9、§16） */
@ExtendWith(MockitoExtension.class)
class MaterialImportTest {

    private static final String[] MATERIAL_HEADERS = {"物料类型", "物料分类", "季节", "物料编码", "物料来源", "物料名称", "单位",
            "门幅", "创建人", "产地"};
    private static final String[] SKU_HEADERS = {"物料编码", "物料SKU编码", "颜色编码", "颜色名称", "规格名称"};

    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private MaterialSkuMapper materialSkuMapper;
    @Mock
    private MaterialCategoryMapper materialCategoryMapper;
    @Mock
    private ColorMapper colorMapper;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private DocLogService docLogService;

    private MaterialImport materialImport;

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> c : List.of(Material.class, MaterialSku.class, MaterialCategory.class, Color.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        materialImport = new MaterialImport(materialMapper, materialSkuMapper, materialCategoryMapper, colorMapper,
                dictDataService, serialNoGenerator, new TradeFeature(false), docLogService);
        lenient().when(dictDataService.listByType("material_type")).thenReturn(List.of(new DictOption("面料", "FABRIC", 1)));
        lenient().when(dictDataService.listByType("unit")).thenReturn(List.of(new DictOption("米", "M", 1)));
        lenient().when(dictDataService.listByType("season")).thenReturn(List.of(new DictOption("夏", "SUMMER", 1)));
        lenient().when(materialCategoryMapper.selectList(isNull())).thenReturn(List.of(
                category(1L, null, 1, null, "面料"), category(5L, 1L, 2, "ML", "梭织")));
        lenient().when(colorMapper.selectList(any())).thenReturn(List.of(color(11L, "KQ", "卡其")));
        AtomicLong ids = new AtomicLong(100);
        lenient().doAnswer(inv -> {
            inv.<Material>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(materialMapper).insert(any(Material.class));
        lenient().doAnswer(inv -> {
            inv.<Color>getArgument(0).setId(ids.incrementAndGet());
            return 1;
        }).when(colorMapper).insert(any(Color.class));
        // 没提到的查询（已有的物料编码、SKU 码，颜色重名）Mockito 默认返回空列表、false：都没有
    }

    /**
     * 规则：领猫的"物料信息" + "物料信息SKU"能导。编码照用；三级路径对前两级、完整路径进附加属性；
     * SKU 码不填按 物料编码-颜色编码 拼；没有 SKU 行的物料建一个码等于物料编码的 SKU；
     * 颜色 ZQ 系统里没有，新建并进提示；客供按我方采购导、进提示；导完把分类 ML 的流水推到 12
     */
    @Test
    void importFile_lingmaoTwoSheets() throws IOException {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息", new Object[][]{MATERIAL_HEADERS,
                {"面料", "面料>梭织", "夏季", "ML0007", "自购", "全棉府绸", "米", 145, "张三", "广东"},
                {"面料", "面料 > 梭织 > 府绸", null, "ML0012", "客供", "涤纶里布", "米", 0, null, null}});
        sheets.put("物料信息SKU", new Object[][]{SKU_HEADERS,
                {"ML0007", "ML0007-KQ", "KQ", "卡其", null},
                {"ML0007", null, "zq", "藏青", null}});

        ImportResult result = materialImport.importFile(xlsx(sheets));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.notes()).extracting(ImportError::message).anySatisfy(m -> assertThat(m).contains("颜色 ZQ 系统里没有，按「ZQ 藏青」新建了"));
        assertThat(result.notes()).extracting(ImportError::message).anySatisfy(m -> assertThat(m).contains("第 3 行的物料来源「客供」按「我方采购」导入"));

        ArgumentCaptor<Material> materials = ArgumentCaptor.captor();
        verify(materialMapper, times(2)).insert(materials.capture());
        Material first = materials.getAllValues().get(0);
        Material second = materials.getAllValues().get(1);
        assertThat(first.getCode()).isEqualTo("ML0007");
        assertThat(first.getCategoryId()).isEqualTo(5L);
        assertThat(first.getSeason()).isEqualTo("SUMMER");
        assertThat(first.getMaterialType()).isEqualTo("FABRIC");
        assertThat(first.getStockUnit()).isEqualTo("M");
        assertThat(first.getWidthCm()).isEqualByComparingTo("145");
        assertThat(first.getLegacyAttrs()).containsEntry("产地", "广东");
        assertThat(second.getDefaultSource()).isEqualTo("PURCHASE");
        assertThat(second.getWidthCm()).isNull();                                  // 领猫没填导出 0，当没填
        assertThat(second.getLegacyAttrs()).containsEntry("物料分类", "面料 > 梭织 > 府绸");

        ArgumentCaptor<Color> color = ArgumentCaptor.captor();
        verify(colorMapper).insert(color.capture());
        assertThat(color.getValue().getCode()).isEqualTo("ZQ");
        ArgumentCaptor<MaterialSku> skus = ArgumentCaptor.captor();
        verify(materialSkuMapper, times(3)).insert(skus.capture());
        assertThat(skus.getAllValues()).extracting(MaterialSku::getCode).containsExactly("ML0007-KQ", "ML0007-ZQ", "ML0012");
        assertThat(skus.getAllValues().get(0).getColorId()).isEqualTo(11L);
        assertThat(skus.getAllValues().get(1).getColorId()).isEqualTo(color.getValue().getId());
        assertThat(skus.getAllValues().get(2).getColorId()).isNull();
        verify(serialNoGenerator).raiseTo("ML", 12);
    }

    /** 规则：有不认识的列（多半是改了名），整个文件不收，1003，一行都不写 */
    @Test
    void importFile_unknownHeader_1003() throws IOException {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息", new Object[][]{{"物料类型", "物料分类", "物料编码", "物料名称", "单位", "瞎写的列"},
                {"面料", "面料>梭织", "ML0007", "全棉府绸", "米", "x"}});

        BizException e = catchThrowableOfType(BizException.class, () -> materialImport.importFile(xlsx(sheets)));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        assertThat(errors(e)).anySatisfy(err -> {
            assertThat(err.row()).isEqualTo(1);
            assertThat(err.column()).isEqualTo("瞎写的列");
        });
        verify(materialMapper, never()).insert(any(Material.class));
    }

    /** 规则：有一行的分类对不上，整批不导（别的行对得上也不导），报出是哪一行 */
    @Test
    void importFile_categoryNotFound_wholeBatchRejected() throws IOException {
        Map<String, Object[][]> sheets = new LinkedHashMap<>();
        sheets.put("物料信息", new Object[][]{{"物料类型", "物料分类", "物料编码", "物料名称", "单位"},
                {"面料", "面料>梭织", "ML0007", "全棉府绸", "米"},
                {"面料", "辅料>纽扣", "NK0001", "树脂扣", "米"}});

        BizException e = catchThrowableOfType(BizException.class, () -> materialImport.importFile(xlsx(sheets)));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        assertThat(errors(e)).singleElement().satisfies(err -> {
            assertThat(err.row()).isEqualTo(3);
            assertThat(err.message()).contains("物料分类「辅料>纽扣」系统里没有");
        });
        verify(materialMapper, never()).insert(any(Material.class));
        verify(serialNoGenerator, never()).raiseTo(any(), any(Integer.class));
    }

    @SuppressWarnings("unchecked")
    private static List<ImportError> errors(BizException e) {
        return (List<ImportError>) e.getData();
    }

    /** 现做一个几张表的 .xlsx：表名 → 行（第一行是表头） */
    private static MockMultipartFile xlsx(Map<String, Object[][]> sheets) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            sheets.forEach((name, rows) -> {
                Sheet sheet = workbook.createSheet(name);
                for (int i = 0; i < rows.length; i++) {
                    Row row = sheet.createRow(i);
                    for (int j = 0; j < rows[i].length; j++) {
                        Object v = rows[i][j];
                        if (v instanceof Number n) {
                            row.createCell(j).setCellValue(n.doubleValue());
                        } else if (v != null) {
                            row.createCell(j).setCellValue(v.toString());
                        }
                    }
                }
            });
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "物料.xlsx", Excel.CONTENT_TYPE, out.toByteArray());
        }
    }

    private static MaterialCategory category(Long id, Long parentId, int level, String code, String name) {
        MaterialCategory c = new MaterialCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setLevel(level);
        c.setCode(code);
        c.setName(name);
        c.setStatus(1);
        return c;
    }

    private static Color color(Long id, String code, String name) {
        Color c = new Color();
        c.setId(id);
        c.setCode(code);
        c.setName(name);
        c.setStatus(1);
        return c;
    }
}
