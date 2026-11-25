package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.seq.SerialNoGenerator;
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
