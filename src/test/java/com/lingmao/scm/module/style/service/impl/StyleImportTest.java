package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.basic.dto.SizeGroupVO;
import com.lingmao.scm.module.style.entity.Style;
import com.lingmao.scm.module.style.entity.StyleSku;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 款式导入（RFC 005 §8、§12 StyleImportTest；PRD §10）。表头用我们自己的模板，数据都是编的
 */
@ExtendWith(MockitoExtension.class)
class StyleImportTest extends StyleTestBase {

    private static final String[] HEADERS = {"款号", "款式名称", "花名", "大类", "品牌", "品类", "年份", "季节", "设计师", "尺码组",
            "颜色", "状态", "等级"};

    @BeforeEach
    void setUp() {
        lenient().when(colorService.options()).thenReturn(List.of(ColorOption.from(KQ), ColorOption.from(ZQ),
                ColorOption.from(BK)));
        lenient().when(sizeGroupService.list()).thenReturn(List.of(new SizeGroupVO(SIZE_GROUP, "测试字母码", "M", 1, SIZES)));
        lenient().when(userService.idsByNickname(any())).thenReturn(Map.of("甲某", DESIGNER_07));
        // 没提到的查询（已有的款号）Mockito 默认返回空列表：都没有
    }

    /**
     * 规则：一行一款，颜色编码、名称混着写、逗号隔开，导完按 颜色 × 尺码组 生成 15 个 SKU；款号照用老款号；
     * 季节"春季"去掉"季"对上；设计师按显示名对上；等级进领猫附加属性；状态不填 = 启用
     */
    @Test
    void importFile_oneRowThreeColors_fifteenSkus() throws IOException {
        ImportResult result = styleExcel.importFile(xlsx(new Object[][]{HEADERS,
                {"27CF009", "测试双排扣风衣", "小风衣", "服饰", "测试品牌", "测试女装/测试上衣/测试衬衫", 2027, "春季", "甲某", "测试字母码",
                        "KQ,藏青，黑", null, "A"}}));

        assertThat(result.total()).isEqualTo(1);
        ArgumentCaptor<Style> style = ArgumentCaptor.captor();
        verify(styleMapper).insert(style.capture());
        Style s = style.getValue();
        assertThat(s.getCode()).isEqualTo("27CF009");
        assertThat(s.getStyleClass()).isEqualTo("FS");
        assertThat(s.getBrand()).isEqualTo("TB");
        assertThat(s.getCategoryId()).isEqualTo(CAT_SHIRT);
        assertThat(s.getSeason()).isEqualTo("SPRING");
        assertThat(s.getSource()).isEqualTo("OWN");
        assertThat(s.getDesignerId()).isEqualTo(DESIGNER_07);
        assertThat(s.getStatus()).isEqualTo(StyleStatus.ENABLED);
        assertThat(s.getLegacyAttrs()).containsEntry("等级", "A");

        ArgumentCaptor<List<StyleSku>> skus = ArgumentCaptor.captor();
        verify(styleSkuMapper).insertMissing(skus.capture(), any(), any());
        assertThat(skus.getValue()).hasSize(15);
        assertThat(skus.getValue().getFirst().getCode()).isEqualTo("27CF009-KQ-S");
        assertThat(skus.getValue().getLast().getCode()).isEqualTo("27CF009-BK-XXL");
        // 导入不推流水、不锁设计师编号
        verify(serialNoGenerator, never()).next(anyString(), anyInt());
        verify(userService, never()).lockDesignerNo(anyLong(), anyString());
    }

    /** 规则：品牌字典里没有的，校验都过了以后在字典里新建一项（value = 名称），进提示 */
    @Test
    void importFile_unknownBrand_createdWithNote() throws IOException {
        ImportResult result = styleExcel.importFile(xlsx(new Object[][]{HEADERS,
                {"27CF010", "测试衬衫", null, "FS", "新测试品牌", "测试女装/测试上衣/测试衬衫", 2027, "夏", null, "测试字母码", "KQ", "草稿", null}}));

        ArgumentCaptor<DictDataSaveReq> brand = ArgumentCaptor.captor();
        verify(dictDataService).create(brand.capture());
        assertThat(brand.getValue().getTypeCode()).isEqualTo("brand");
        assertThat(brand.getValue().getLabel()).isEqualTo("新测试品牌");
        assertThat(brand.getValue().getValue()).isEqualTo("新测试品牌");
        assertThat(result.notes()).extracting(ImportError::message)
                .anySatisfy(m -> assertThat(m).contains("品牌「新测试品牌」字典里没有，导入时新建了一项"));
        ArgumentCaptor<Style> style = ArgumentCaptor.captor();
        verify(styleMapper).insert(style.capture());
        assertThat(style.getValue().getBrand()).isEqualTo("新测试品牌");
        assertThat(style.getValue().getStatus()).isEqualTo(StyleStatus.DRAFT);
    }

    /** 规则：颜色有一个对不上，整批不导（1003），品牌也不建 */
    @Test
    void importFile_unknownColor_wholeBatchRejected() throws IOException {
        MockMultipartFile file = xlsx(new Object[][]{HEADERS,
                {"27CF011", "测试衬衫", null, "FS", "新测试品牌", "测试女装/测试上衣/测试衬衫", 2027, "春", null, "测试字母码", "KQ", null, null},
                {"27CF012", "测试长裙", null, "FS", "测试品牌", "测试女装/测试上衣/测试衬衫", 2027, "春", null, "测试字母码", "KQ,荧光绿", null, null}});

        BizException e = catchThrowableOfType(BizException.class, () -> styleExcel.importFile(file));

        assertThat(e.getCode()).isEqualTo(ErrorCodes.IMPORT_INVALID);
        @SuppressWarnings("unchecked")
        List<ImportError> errors = (List<ImportError>) e.getData();
        assertThat(errors).singleElement().satisfies(err -> {
            assertThat(err.row()).isEqualTo(3);
            assertThat(err.column()).isEqualTo("颜色");
            assertThat(err.message()).contains("颜色「荧光绿」系统里没有");
        });
        verify(styleMapper, never()).insert(any(Style.class));
        verify(dictDataService, never()).create(any());
    }

    /** 规则：状态填启用（或不填）但没有颜色的，生成不了 SKU，导成草稿、进提示；设计师对不上的留空、进提示 */
    @Test
    void importFile_enabledWithoutColors_draftWithNote() throws IOException {
        ImportResult result = styleExcel.importFile(xlsx(new Object[][]{HEADERS,
                {"27CF013", "测试半裙", null, "FS", "测试品牌", "测试女装/测试上衣/测试衬衫", 2027, "春", "查无此人", "测试字母码", null, "启用", null}}));

        ArgumentCaptor<Style> style = ArgumentCaptor.captor();
        verify(styleMapper).insert(style.capture());
        assertThat(style.getValue().getStatus()).isEqualTo(StyleStatus.DRAFT);
        assertThat(style.getValue().getDesignerId()).isNull();
        assertThat(result.notes()).extracting(ImportError::message)
                .anySatisfy(m -> assertThat(m).contains("没有颜色，生成不了 SKU，导成了草稿"))
                .anySatisfy(m -> assertThat(m).contains("设计师「查无此人」对不上用户"));
        verify(styleSkuMapper, never()).insertMissing(any(), any(), any());
    }

    private static MockMultipartFile xlsx(Object[][] rows) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("数据");
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
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return new MockMultipartFile("file", "款式.xlsx", Excel.CONTENT_TYPE, out.toByteArray());
        }
    }
}
