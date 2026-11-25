package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.MaterialCategorySaveReq;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.service.CodeRuleService;
import com.lingmao.scm.module.system.service.DictDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 编码规则在真库上（RFC 018 §4）：V57 预置的规则从 JSONB 读出来拼的和改之前写死的一样、流水 key 也一样（接着编）；
 * 给某个大类加了规则，预览按新规则；规则里有品类编码、品类没填时报错；两个请求同时给同一个大类加规则只成一个。
 * 每个测试自己加一个没人用的大类，测完删掉规则和大类，不影响别的测试用的 FS、XZ
 */
@IntegrationTest
class CodeRuleIT {

    @Autowired
    private CodeRuleService codeRuleService;
    @Autowired
    private StyleService styleService;
    @Autowired
    private DictDataService dictDataService;
    @Autowired
    private SerialNoGenerator serialNoGenerator;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private Clock clock;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MaterialService materialService;
    @Autowired
    private MaterialCategoryService materialCategoryService;

    /** 规则（RFC 018 §4.5）：预置的三条和改之前一样：FS+yyMMdd+07+01、ML+4 位、GYS+4 位；推过的流水接着往下取 */
    @Test
    void presetRules_sameAsBefore_serialContinues() {
        String today = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyMMdd"));
        CodeTemplate style = codeRuleService.resolve(CodeTarget.STYLE, CodeContext.of("FS")
                .with(CodeSegment.Type.STYLE_CLASS, () -> "FS").with(CodeSegment.Type.DESIGNER, () -> "07"));
        assertThat(style.key()).isEqualTo("FS" + today + "07");
        assertThat(style.format(1)).isEqualTo("FS" + today + "0701");

        String categoryCode = "Q" + (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        CodeTemplate material = codeRuleService.resolve(CodeTarget.MATERIAL, CodeContext.of("FABRIC")
                .with(CodeSegment.Type.MAT_CATEGORY, () -> categoryCode));
        assertThat(material.key()).isEqualTo(categoryCode);
        assertThat(material.format(42)).isEqualTo(categoryCode + "0042");
        assertThat(codeRuleService.resolve(CodeTarget.SUPPLIER, CodeContext.of(null)).format(7)).isEqualTo("GYS0007");

        String key = categoryCode + "-IT" + System.nanoTime() % 100000;
        Integer next = tx.execute(s -> {
            serialNoGenerator.raiseTo(key, 41);
            return serialNoGenerator.nextSeq(key);
        });
        assertThat(next).isEqualTo(42);
    }

    /** 规则：给一个大类加了规则，预览按它编；改成带品类编码的规则后，品类没填编码报错，填了就带上 */
    @Test
    void scopedRule_previewFollows_categoryCodeRequired() {
        String cls = freeStyleClass();
        Long dictId = dictDataService.create(dict(cls));
        Long ruleId = null;
        Long categoryId = null;
        try {
            ruleId = codeRuleService.create(rule(cls, text("Z" + cls), seg(CodeSegment.Type.STYLE_YEAR), seq(3)));
            assertThat(styleService.codePreview(cls, null, null, null, 2027)).isEqualTo("Z" + cls + "27001");

            codeRuleService.update(ruleId, rule(cls, text("Z" + cls), seg(CodeSegment.Type.CATEGORY), seq(3)));
            categoryId = jdbc.queryForObject("INSERT INTO bas_category (name, level) VALUES (?, 1) RETURNING id", Long.class,
                    "编码测试" + cls);
            long cat = categoryId;
            assertThatThrownBy(() -> styleService.codePreview(cls, null, null, cat, null))
                    .isInstanceOf(BizException.class).hasMessageContaining("还没填编码");
            jdbc.update("UPDATE bas_category SET code = 'QQ' WHERE id = ?", cat);
            assertThat(styleService.codePreview(cls, null, null, cat, null)).isEqualTo("Z" + cls + "QQ001");
        } finally {
            if (ruleId != null) {
                codeRuleService.delete(ruleId);
            }
            if (categoryId != null) {
                jdbc.update("DELETE FROM bas_category WHERE id = ?", categoryId);
            }
            dictDataService.delete(dictId);
        }
    }

    /** 规则：两个请求同时给同一个大类加规则：先锁通用规则那一行，排着队查重，只成一个，另一个报"已经在规则里了" */
    @Test
    void concurrentSaves_onlyOneWins() throws Exception {
        String cls = freeStyleClass();
        Long dictId = dictDataService.create(dict(cls));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Long> created = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Long> save = () -> {
                start.await();
                return codeRuleService.create(rule(cls, text("Y" + cls), seq(3)));
            };
            List<Future<Long>> futures = List.of(pool.submit(save), pool.submit(save));
            start.countDown();
            List<String> failures = new ArrayList<>();
            for (Future<Long> f : futures) {
                try {
                    created.add(f.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(BizException.class);
                    failures.add(e.getCause().getMessage());
                }
            }
            assertThat(created).hasSize(1);
            assertThat(failures).singleElement().asString().contains("已经在规则");
        } finally {
            pool.shutdownNow();
            created.forEach(codeRuleService::delete);
            dictDataService.delete(dictId);
        }
    }

    /**
     * 规则（RFC 018 §8，V58）：甲方 11-25 的两条规则在真库上编得出来。
     * 款号：品牌编码取字典「品牌」上填的编码（值是中文名）。物料编码：年份 + 一级分类码 + 二级分类码 + "-" + 3 位流水；
     * 二级的 03 在两个一级下面都能建（V58 换了唯一索引），同一个一级下第二个 03 由 Service 和索引两头拦；一级没填编码报清楚
     */
    @Test
    void clientRules1125_brandCodeAndMaterialCategoryCodes() {
        String cls = freeStyleClass();
        String suffix = cls;
        Long classId = dictDataService.create(dict(cls));
        DictDataSaveReq brand = new DictDataSaveReq();
        brand.setTypeCode("brand");
        brand.setLabel("编码测试品牌" + suffix);
        brand.setValue("编码测试品牌" + suffix);
        brand.setTextValue("S");
        Long brandId = dictDataService.create(brand);
        DictDataSaveReq type = new DictDataSaveReq();
        type.setTypeCode("material_type");
        type.setLabel("编码测试类型" + suffix);
        type.setValue("ITM" + suffix);
        Long typeId = dictDataService.create(type);
        List<Long> rulesMade = new ArrayList<>();
        List<Long> tops = new ArrayList<>();
        List<Long> leaves = new ArrayList<>();
        Long styleCategory = null;
        try {
            // 款号：F + 品牌编码 + 年份 + Z + 品类编码 + - + 3 位流水
            rulesMade.add(codeRuleService.create(rule(cls, text("F"), seg(CodeSegment.Type.BRAND), seg(CodeSegment.Type.STYLE_YEAR),
                    text("Z"), seg(CodeSegment.Type.CATEGORY), text("-"), seq(3))));
            styleCategory = jdbc.queryForObject("INSERT INTO bas_category (name, level, code) VALUES (?, 1, '33') RETURNING id", Long.class,
                    "编码测试" + suffix);
            assertThat(styleService.codePreview(cls, null, "编码测试品牌" + suffix, styleCategory, 2026)).isEqualTo("FS26Z33-001");

            // 物料编码：两个一级（LL 这种），下面各一个 03；第三个一级不填编码
            String top1 = freeTopCode();
            tops.add(materialCategoryService.create(category(null, top1, "编码测试一级A" + suffix)));
            String top2 = freeTopCode();
            tops.add(materialCategoryService.create(category(null, top2, "编码测试一级B" + suffix)));
            tops.add(materialCategoryService.create(category(null, null, "编码测试一级C" + suffix)));
            for (Long top : tops) {
                leaves.add(materialCategoryService.create(category(top, "03", "编码测试二级" + suffix)));
            }
            long leaf1 = leaves.get(0);
            long leaf2 = leaves.get(1);
            long leaf3 = leaves.get(2);
            long parent1 = tops.get(0);
            assertThatThrownBy(() -> materialCategoryService.create(category(parent1, "03", "编码测试重复" + suffix)))
                    .isInstanceOf(BizException.class).hasMessageContaining("在这个上级下已经有了");
            assertThatThrownBy(() -> jdbc.update("INSERT INTO bas_material_category (parent_id, code, name, level) VALUES (?, '03', ?, 2)",
                    parent1, "编码测试绕过" + suffix)).isInstanceOf(DataIntegrityViolationException.class);

            CodeRuleSaveReq material = new CodeRuleSaveReq();
            material.setTarget(CodeTarget.MATERIAL);
            material.setName("测试" + suffix);
            material.setScopeValues(List.of("ITM" + suffix));
            material.setSegments(List.of(new CodeSegment(CodeSegment.Type.DATE, null, "yy", null), seg(CodeSegment.Type.MAT_PARENT_CATEGORY),
                    seg(CodeSegment.Type.MAT_CATEGORY), text("-"), seq(3)));
            rulesMade.add(codeRuleService.create(material));
            String yy = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yy"));
            assertThat(materialService.codePreview(leaf1, "ITM" + suffix)).isEqualTo(yy + top1 + "03-001");
            assertThat(materialService.codePreview(leaf2, "ITM" + suffix)).isEqualTo(yy + top2 + "03-001");
            assertThatThrownBy(() -> materialService.codePreview(leaf3, "ITM" + suffix))
                    .isInstanceOf(BizException.class).hasMessageContaining("还没填编码");
        } finally {
            rulesMade.forEach(codeRuleService::delete);
            leaves.forEach(id -> jdbc.update("DELETE FROM bas_material_category WHERE id = ?", id));
            tops.forEach(id -> jdbc.update("DELETE FROM bas_material_category WHERE id = ?", id));
            if (styleCategory != null) {
                jdbc.update("DELETE FROM bas_category WHERE id = ?", styleCategory);
            }
            dictDataService.delete(typeId);
            dictDataService.delete(brandId);
            dictDataService.delete(classId);
        }
    }

    /** 一级物料分类还没用的两位编码 */
    private String freeTopCode() {
        for (int i = 0; i < 200; i++) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            String code = "" + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
            Integer used = jdbc.queryForObject("SELECT count(*) FROM bas_material_category WHERE parent_id IS NULL AND code = ?",
                    Integer.class, code);
            if (used == 0) {
                return code;
            }
        }
        throw new IllegalStateException("两位字母的一级分类编码都被用了：重建 IT 库再跑");
    }

    private static MaterialCategorySaveReq category(Long parentId, String code, String name) {
        MaterialCategorySaveReq req = new MaterialCategorySaveReq();
        req.setParentId(parentId);
        req.setCode(code);
        req.setName(name);
        req.setSort(0);
        return req;
    }

    /** 两位大写字母、字典里还没有的大类 */
    private String freeStyleClass() {
        for (int i = 0; i < 200; i++) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            String value = "" + (char) ('A' + r.nextInt(26)) + (char) ('A' + r.nextInt(26));
            Integer used = jdbc.queryForObject("SELECT count(*) FROM sys_dict_data WHERE type_code = 'style_class' AND value = ?",
                    Integer.class, value);
            if (used == 0) {
                return value;
            }
        }
        throw new IllegalStateException("两位字母的大类都被用了：重建 IT 库再跑");
    }

    private static DictDataSaveReq dict(String value) {
        DictDataSaveReq req = new DictDataSaveReq();
        req.setTypeCode("style_class");
        req.setLabel("编码测试" + value);
        req.setValue(value);
        return req;
    }

    private static CodeRuleSaveReq rule(String styleClass, CodeSegment... segments) {
        CodeRuleSaveReq req = new CodeRuleSaveReq();
        req.setTarget(CodeTarget.STYLE);
        req.setName("测试" + styleClass);
        req.setScopeValues(List.of(styleClass));
        req.setSegments(List.of(segments));
        return req;
    }

    private static CodeSegment seg(CodeSegment.Type type) {
        return new CodeSegment(type, null, null, null);
    }

    private static CodeSegment text(String text) {
        return new CodeSegment(CodeSegment.Type.TEXT, text, null, null);
    }

    private static CodeSegment seq(int width) {
        return new CodeSegment(CodeSegment.Type.SEQ, null, null, width);
    }
}
