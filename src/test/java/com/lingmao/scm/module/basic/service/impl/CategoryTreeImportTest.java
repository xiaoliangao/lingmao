package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.DirectFileImports;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.entity.Category;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.mapper.CategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CategoryTreeImportTest {

    /** 领猫"基础档案 › 物料类目 / 产品类目"导出的列 */
    static final String[] HEADERS = {"分类名称", "分类编码", "状态", "扩展一", "扩展二", "扩展三", "扩展四", "扩展七", "描述", "排序"};

    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private MaterialCategoryMapper materialCategoryMapper;

    private CategoryTreeImport importer;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Category.class);
        TableInfoHelper.initTableInfo(assistant, MaterialCategory.class);
    }

    @BeforeEach
    void setUp() {
        importer = new CategoryTreeImport(categoryMapper, materialCategoryMapper, DirectFileImports.INSTANCE);
    }

    /**
     * 规则（RFC 018 §8.7）：空的物料分类树导领猫的表，先建一级再建二级，二级挂到对应的一级下；
     * 二级编码在不同的一级下可以一样（03），状态、排序照表
     */
    @Test
    void importExcel_material_buildsTreeParentsFirst() throws IOException {
        when(materialCategoryMapper.selectList(isNull())).thenReturn(List.of());
        List<MaterialCategory> inserted = captureMaterialInserts();

        ImportResult result = importer.importExcel(CategoryTreeImport.Kind.MATERIAL, file(
                row("拉链>金属拉链", "03", "启用", "0"),
                row("拉链", "LL", "启用", "2"),
                row("纽扣>树脂扣", "03", "停用", "0"),
                row("纽扣", "nk", "启用", "1")));

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.notes()).isEmpty();
        assertThat(inserted).extracting(MaterialCategory::getName).containsExactly("拉链", "纽扣", "金属拉链", "树脂扣");
        Map<String, MaterialCategory> byName = inserted.stream().collect(Collectors.toMap(MaterialCategory::getName, c -> c));
        assertThat(byName.get("纽扣").getCode()).isEqualTo("NK");
        assertThat(byName.get("金属拉链").getParentId()).isEqualTo(byName.get("拉链").getId());
        assertThat(byName.get("树脂扣").getParentId()).isEqualTo(byName.get("纽扣").getId());
        assertThat(byName.get("树脂扣").getStatus()).isEqualTo(Statuses.DISABLED);
        assertThat(byName.get("金属拉链").getLevel()).isEqualTo(2);
        assertThat(byName.get("拉链").getSort()).isEqualTo(2);
    }

    /**
     * 规则：已有的按路径对上，只改编码和状态，表里空着的不清掉；一样的不写库。
     * 改了的、没变的行号进提示
     */
    @Test
    void importExcel_material_existingUpdatedOnlyWhenChanged() throws IOException {
        when(materialCategoryMapper.selectList(isNull())).thenReturn(List.of(
                material(1L, null, "拉链", null, 1), material(2L, 1L, "金属拉链", "03", 1), material(3L, 1L, "树脂拉链", "05", 1)));
        when(materialCategoryMapper.selectById(1L)).thenReturn(material(1L, null, "拉链", null, 1));
        when(materialCategoryMapper.selectById(3L)).thenReturn(material(3L, 1L, "树脂拉链", "05", 1));
        List<MaterialCategory> inserted = captureMaterialInserts();
        ArgumentCaptor<MaterialCategory> updated = ArgumentCaptor.forClass(MaterialCategory.class);

        ImportResult result = importer.importExcel(CategoryTreeImport.Kind.MATERIAL, file(
                row("拉链", "LL", "", ""),
                row("拉链>金属拉链", "", "", ""),
                row("拉链>树脂拉链", "05", "停用", ""),
                row("拉链>尼龙拉链", "07", "", "")));

        verify(materialCategoryMapper, org.mockito.Mockito.times(2)).update(updated.capture(), any());
        assertThat(updated.getAllValues()).extracting(MaterialCategory::getId, MaterialCategory::getCode, MaterialCategory::getStatus)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, "LL", 1), org.assertj.core.groups.Tuple.tuple(3L, "05", 0));
        assertThat(inserted).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("尼龙拉链");
            assertThat(c.getParentId()).isEqualTo(1L);
        });
        assertThat(result.notes()).extracting(ImportError::message).containsExactly(
                "第 2、4 行是系统里已有的物料分类，按表改了编码或状态",
                "第 3 行和系统里已有的一样，没改");
    }

    /**
     * 规则：有一行不对整批不导，错误逐条报：新二级没编码、编码被 Excel 吃掉前面的 0、同一个上级下编码重复（和系统里的也算）、
     * 超过两级、上级哪儿都没有、状态写错
     */
    @Test
    void importExcel_material_badRowsRejectWholeFile() throws IOException {
        when(materialCategoryMapper.selectList(isNull())).thenReturn(List.of(
                material(1L, null, "拉链", "LL", 1), material(2L, 1L, "金属拉链", "03", 1)));

        assertThatThrownBy(() -> importer.importExcel(CategoryTreeImport.Kind.MATERIAL, file(
                row("拉链>树脂拉链", "", "", ""),
                row("拉链>尼龙拉链", "3", "", ""),
                row("拉链>隐形拉链", "03", "", ""),
                row("拉链>金属拉链>铜", "01", "", ""),
                row("纽扣>树脂扣", "01", "", ""),
                row("拉链>防水拉链", "09", "暂停", ""))))
                .isInstanceOfSatisfying(BizException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(1003);
                    @SuppressWarnings("unchecked")
                    List<ImportError> errors = (List<ImportError>) ex.getData();
                    assertThat(errors).extracting(ImportError::row, ImportError::message).containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(2, "二级分类要填 2 位编码（字母或数字）：物料编码里要用"),
                            org.assertj.core.groups.Tuple.tuple(3, "编码「3」不对，物料分类的编码是 2 位字母或数字：可能是 Excel 把 03 当成了数字，把这一列设成文本、重新填"),
                            org.assertj.core.groups.Tuple.tuple(4, "编码 03 和系统里的「拉链>金属拉链」一样：同一个上级下编码不能重复"),
                            org.assertj.core.groups.Tuple.tuple(5, "物料分类只有 2 级，「拉链>金属拉链>铜」是 3 级"),
                            org.assertj.core.groups.Tuple.tuple(6, "上级「纽扣」系统里没有，表里也没有：先在表里加上它"),
                            org.assertj.core.groups.Tuple.tuple(7, "状态写 启用 或 停用"));
                });
        verify(materialCategoryMapper, never()).insert(any(MaterialCategory.class));
        verify(materialCategoryMapper, never()).update(any(), any());
    }

    /** 规则：品类三级，一级编码是 F 这种 1 位字母，三级编码在不同的上级下可以一样 */
    @Test
    void importExcel_category_threeLevels() throws IOException {
        when(categoryMapper.selectList(isNull())).thenReturn(List.of());
        AtomicLong ids = new AtomicLong(100);
        List<Category> inserted = new ArrayList<>();
        doAnswer(inv -> {
            Category c = inv.getArgument(0);
            c.setId(ids.incrementAndGet());
            inserted.add(c);
            return 1;
        }).when(categoryMapper).insert(any(Category.class));

        ImportResult result = importer.importExcel(CategoryTreeImport.Kind.CATEGORY, file(
                row("服装", "F", "启用", "0"),
                row("服装>裙子", "27", "启用", "0"),
                row("服装>裙子>半身裙", "95", "启用", "0"),
                row("通勤裤", "T", "启用", "0"),
                row("通勤裤>西裤", "40", "启用", "0"),
                row("通勤裤>西裤>直筒", "95", "启用", "0")));

        assertThat(result.total()).isEqualTo(6);
        assertThat(inserted).extracting(Category::getName, Category::getLevel).containsExactly(
                org.assertj.core.groups.Tuple.tuple("服装", 1), org.assertj.core.groups.Tuple.tuple("通勤裤", 1),
                org.assertj.core.groups.Tuple.tuple("裙子", 2), org.assertj.core.groups.Tuple.tuple("西裤", 2),
                org.assertj.core.groups.Tuple.tuple("半身裙", 3), org.assertj.core.groups.Tuple.tuple("直筒", 3));
        Map<String, Category> byName = inserted.stream().collect(Collectors.toMap(Category::getName, c -> c));
        assertThat(byName.get("直筒").getParentId()).isEqualTo(byName.get("西裤").getId());
        assertThat(byName.get("直筒").getCode()).isEqualTo("95");
    }

    private List<MaterialCategory> captureMaterialInserts() {
        AtomicLong ids = new AtomicLong(100);
        List<MaterialCategory> inserted = new ArrayList<>();
        doAnswer(inv -> {
            MaterialCategory c = inv.getArgument(0);
            c.setId(ids.incrementAndGet());
            inserted.add(c);
            return 1;
        }).when(materialCategoryMapper).insert(any(MaterialCategory.class));
        return inserted;
    }

    private static MaterialCategory material(Long id, Long parentId, String name, String code, int status) {
        MaterialCategory c = new MaterialCategory();
        c.setId(id);
        c.setParentId(parentId);
        c.setName(name);
        c.setCode(code);
        c.setLevel(parentId == null ? 1 : 2);
        c.setStatus(status);
        return c;
    }

    /** 分类名称、分类编码、状态、排序；扩展列和描述空着 */
    private static String[] row(String path, String code, String status, String sort) {
        return new String[]{path, code, status, null, null, null, null, null, null, sort};
    }

    private static MockMultipartFile file(String[]... rows) throws IOException {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = book.createSheet("基础档案物料类目");
            XSSFRow header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                XSSFRow row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    String v = rows[r][c];
                    if (v == null || v.isEmpty()) {
                        continue;
                    }
                    if (c == 9) {
                        row.createCell(c).setCellValue(Integer.parseInt(v));   // 排序是数字
                    } else {
                        row.createCell(c).setCellValue(v);
                    }
                }
            }
            book.write(out);
            return new MockMultipartFile("file", "类目.xlsx", "application/octet-stream", out.toByteArray());
        }
    }
}
