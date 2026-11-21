package com.lingmao.scm.module.basic.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.basic.dto.MaterialSaveReq;
import com.lingmao.scm.module.basic.dto.MaterialTypeReq;
import com.lingmao.scm.module.basic.entity.Color;
import com.lingmao.scm.module.basic.entity.Material;
import com.lingmao.scm.module.basic.entity.MaterialCategory;
import com.lingmao.scm.module.basic.entity.MaterialSku;
import com.lingmao.scm.module.basic.mapper.ColorMapper;
import com.lingmao.scm.module.basic.mapper.MaterialCategoryMapper;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.basic.mapper.MaterialSkuMapper;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 编码 = 分类码 + 4 位、跳过已占的；颜色 × 规格建 SKU；库存单位不能改；TRADE 关时客供被拒（RFC 003 §16） */
@ExtendWith(MockitoExtension.class)
class MaterialServiceImplTest {

    @Mock
    private MaterialMapper materialMapper;
    @Mock
    private MaterialSkuMapper materialSkuMapper;
    @Mock
    private MaterialCategoryMapper materialCategoryMapper;
    @Mock
    private ColorMapper colorMapper;
    @Mock
    private SerialNoGenerator serialNoGenerator;
    @Mock
    private DictDataService dictDataService;
    @Mock
    private AttachmentService attachmentService;
    @Mock
    private DocLogService docLogService;
    @Mock
    private MaterialImport materialImport;

    private MaterialServiceImpl materialService;

    @BeforeAll
    static void initTableInfo() {
        for (Class<?> c : List.of(Material.class, MaterialSku.class, MaterialCategory.class, Color.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), c);
        }
    }

    @BeforeEach
    void setUp() {
        materialService = new MaterialServiceImpl(materialMapper, materialSkuMapper, materialCategoryMapper, colorMapper,
                serialNoGenerator, new DictChecks(dictDataService), new TradeFeature(false),
                attachmentService, docLogService, materialImport, dictDataService);
    }

    /**
     * 规则（RFC 003 §7.1、§7.2）：编码 = 分类码 + 4 位流水，取到的号被导入的老编码占了就取下一个；
     * 选卡其、藏青、黑三个颜色、不填规格，建三个 SKU：编码 + 颜色码
     */
    @Test
    void create_codeSkipsTaken_threeColorsMakeThreeSkus() {
        stubLeafCategory();
        stubDict();
        when(serialNoGenerator.next("ML", 4)).thenReturn("ML0001", "ML0002");
        when(materialMapper.exists(any())).thenReturn(true, false);           // ML0001 被导入的占了
        doAnswer(inv -> {
            inv.<Material>getArgument(0).setId(100L);
            return 1;
        }).when(materialMapper).insert(any(Material.class));
        when(colorMapper.selectByIds(any())).thenReturn(List.of(color(1L, "KQ"), color(2L, "ZQ"), color(3L, "HE")));
        when(materialSkuMapper.selectList(any())).thenReturn(List.of());   // 已有的组合、被占的 SKU 码都没有
        MaterialCreateReq req = createReq();
        req.setColorIds(List.of(1L, 2L, 3L));

        materialService.create(req);

        ArgumentCaptor<Material> material = ArgumentCaptor.captor();
        verify(materialMapper).insert(material.capture());
        assertThat(material.getValue().getCode()).isEqualTo("ML0002");
        assertThat(material.getValue().getPurchaseUnit()).isEqualTo("M");          // 采购单位不填 = 库存单位
        ArgumentCaptor<MaterialSku> skus = ArgumentCaptor.captor();
        verify(materialSkuMapper, times(3)).insert(skus.capture());
        assertThat(skus.getAllValues()).extracting(MaterialSku::getCode)
                .containsExactly("ML0002-KQ", "ML0002-ZQ", "ML0002-HE");
        verify(docLogService).add(any(), eq(100L), eq("create"), eq("ML0002-KQ、ML0002-ZQ、ML0002-HE"));
    }

    /** 规则：库存单位建好不能改（库存、BOM 用量都按它记） */
    @Test
    void update_stockUnitChanged_rejected() {
        Material old = new Material();
        old.setId(100L);
        old.setCategoryId(5L);
        old.setStockUnit("M");
        when(materialMapper.selectById(100L)).thenReturn(old);
        MaterialSaveReq req = createReq();
        req.setStockUnit("KG");

        assertThatThrownBy(() -> materialService.update(100L, req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("库存单位建好就不能改");
        verify(materialMapper, never()).update(any(), any());
    }

    /** 规则（RFC 003 §3.3）：TRADE 关着，默认来源选"客供"报 400，不插入 */
    @Test
    void create_customerSuppliedWhileTradeOff_rejected400() {
        stubLeafCategory();
        MaterialCreateReq req = createReq();
        req.setDefaultSource("CUSTOMER_SUPPLIED");

        assertThatThrownBy(() -> materialService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("客供")
                .extracting("code").isEqualTo(400);
        verify(materialMapper, never()).insert(any(Material.class));
    }

    /** 规则：采购单位和库存单位一样时，换算系数只能是 1 */
    @Test
    void create_sameUnitFactorNotOne_rejected400() {
        stubLeafCategory();
        stubDict();
        MaterialCreateReq req = createReq();
        req.setPurchaseUnit("M");
        req.setConversionFactor(new BigDecimal("1.5"));

        assertThatThrownBy(() -> materialService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("换算系数只能是 1")
                .extracting("code").isEqualTo(400);
    }

    /** 规则（甲方 11-21）：带功能吊牌选了"是"要写吊牌名称（只有空格也不行），不插入 */
    @Test
    void create_withTagButNoTagNames_rejected400() {
        stubLeafCategory();
        stubDict();
        MaterialCreateReq req = createReq();
        req.setWithTag(true);
        req.setTagNames("  ");

        assertThatThrownBy(() -> materialService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("要填吊牌名称")
                .extracting("code").isEqualTo(400);
        verify(materialMapper, never()).insert(any(Material.class));
    }

    /** 规则：领猫导进来的带吊牌物料没有名称，改别的字段照旧能存；原来没带吊牌、这次新选"是"的才必填 */
    @Test
    void update_importedWithTagWithoutNames_stillSaves() {
        Material old = new Material();
        old.setId(100L);
        old.setCategoryId(5L);
        old.setStockUnit("M");
        old.setPurchaseUnit("M");
        old.setMaterialType("FABRIC");
        old.setWithTag(true);
        when(materialMapper.selectById(100L)).thenReturn(old);
        MaterialSaveReq req = createReq();
        req.setWithTag(true);

        materialService.update(100L, req);
        verify(materialMapper).update(any(), any());

        old.setWithTag(false);
        assertThatThrownBy(() -> materialService.update(100L, req))
                .isInstanceOf(BizException.class).hasMessageContaining("要填吊牌名称");
    }

    /** 规则：物料至少留一个 SKU */
    @Test
    void deleteSku_lastOne_rejected() {
        when(materialMapper.lockById(100L)).thenReturn(100L);
        MaterialSku sku = new MaterialSku();
        sku.setId(7L);
        sku.setMaterialId(100L);
        when(materialSkuMapper.selectById(7L)).thenReturn(sku);
        when(materialSkuMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> materialService.deleteSku(100L, 7L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("至少要留一个 SKU");
        verify(materialSkuMapper, never()).deleteById(any(Long.class));
    }

    /**
     * 规则：SKU 全都不带颜色（导入时没有 SKU 表、只建了一个光 SKU 的物料）也能取 SKU 给下游。
     * 原来颜色表是空的时候给的是 Map.of()，拿 null 的颜色 id 去 get 会空指针（第 4b 步价格档案测出来的，详情页同一处）
     */
    @Test
    void skuRefs_skusWithoutColor_ok() {
        MaterialSku sku = new MaterialSku();
        sku.setId(7L);
        sku.setMaterialId(100L);
        sku.setCode("ML0001");
        sku.setStatus(1);
        Material material = new Material();
        material.setId(100L);
        material.setCode("ML0001");
        material.setStatus(1);
        when(materialSkuMapper.selectByIds(List.of(7L))).thenReturn(List.of(sku));
        when(materialMapper.selectByIds(List.of(100L))).thenReturn(List.of(material));

        assertThat(materialService.skuRefs(List.of(7L))).singleElement()
                .satisfies(ref -> assertThat(ref.colorName()).isNull())
                .satisfies(ref -> assertThat(ref.material().code()).isEqualTo("ML0001"));
    }

    /**
     * 规则（PRD §6.1.5 规则 6，Q40）：当场加物料类型，值接着已有的 CUSTOM_ 编号往下编，排在最后，备料比不填 = 0（Q18：由生产评估后填）；
     * 名称和已有的重复（停用的也算）不让加，提示去哪启用
     */
    @Test
    void addType_nextCustomValue_duplicateLabelRejected() {
        when(dictDataService.listWithText("material_type")).thenReturn(List.of(
                dict("面料", "FABRIC", 1, 1), dict("拉链头", "CUSTOM_2", 9, 1), dict("鞋带", "CUSTOM_1", 8, 0)));
        MaterialTypeReq req = new MaterialTypeReq();
        req.setLabel(" 织带 ");

        DictOption added = materialService.addType(req);

        ArgumentCaptor<DictDataSaveReq> saved = ArgumentCaptor.captor();
        verify(dictDataService).create(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo("CUSTOM_3");
        assertThat(saved.getValue().getLabel()).isEqualTo("织带");
        assertThat(saved.getValue().getSort()).isEqualTo(10);
        assertThat(saved.getValue().getNumValue()).isEqualByComparingTo("0");
        assertThat(added).isEqualTo(new DictOption("织带", "CUSTOM_3", 1));

        req.setLabel("鞋带");
        assertThatThrownBy(() -> materialService.addType(req)).isInstanceOf(BizException.class).hasMessageContaining("停用着");
        verify(dictDataService, times(1)).create(any());
    }

    private static DictData dict(String label, String value, int sort, int status) {
        DictData d = new DictData();
        d.setLabel(label);
        d.setValue(value);
        d.setSort(sort);
        d.setStatus(status);
        return d;
    }

    private void stubLeafCategory() {
        MaterialCategory leaf = new MaterialCategory();
        leaf.setId(5L);
        leaf.setParentId(1L);
        leaf.setLevel(2);
        leaf.setCode("ML");
        leaf.setName("梭织面料");
        leaf.setStatus(1);
        MaterialCategory top = new MaterialCategory();
        top.setId(1L);
        top.setLevel(1);
        top.setName("面料");
        top.setStatus(1);
        when(materialCategoryMapper.selectById(5L)).thenReturn(leaf);
        when(materialCategoryMapper.selectById(1L)).thenReturn(top);
    }

    private void stubDict() {
        when(dictDataService.listByType(any())).thenReturn(List.of(
                new DictOption("面料", "FABRIC", 1), new DictOption("米", "M", 1)));
    }

    private static MaterialCreateReq createReq() {
        MaterialCreateReq req = new MaterialCreateReq();
        req.setName("全棉府绸");
        req.setCategoryId(5L);
        req.setMaterialType("FABRIC");
        req.setStockUnit("M");
        return req;
    }

    private static Color color(Long id, String code) {
        Color c = new Color();
        c.setId(id);
        c.setCode(code);
        c.setName(code);
        c.setStatus(1);
        return c;
    }
}
