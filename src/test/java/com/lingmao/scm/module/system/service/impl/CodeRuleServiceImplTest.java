package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.SysCodeRule;
import com.lingmao.scm.module.system.mapper.SysCodeRuleMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeRuleServiceImplTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Mock
    private SysCodeRuleMapper ruleMapper;

    @Mock
    private DictDataService dictDataService;

    private CodeRuleServiceImpl service;

    private final List<SysCodeRule> rules = new ArrayList<>();

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysCodeRule.class);
    }

    @BeforeEach
    void setUp() {
        service = new CodeRuleServiceImpl(ruleMapper, dictDataService, CLOCK);
        lenient().when(ruleMapper.selectList(any())).thenAnswer(inv -> List.copyOf(rules));
        lenient().when(dictDataService.listByType("style_class"))
                .thenReturn(List.of(new DictOption("服饰", "FS", 1), new DictOption("鞋", "XZ", 1)));
        // 通用规则：大类 + yyMMdd + 设计师 + 2 位流水（V57 预置的）
        rules.add(rule(1, "通用规则", List.of(), seg(CodeSegment.Type.STYLE_CLASS), date("yyMMdd"), seg(CodeSegment.Type.DESIGNER), seq(2)));
    }

    /**
     * 规则（RFC 018 §4.5）：写了这个大类的规则优先，没有就用通用规则；
     * 流水在最后时 key 就是前缀（和改成规则以前的 key 一样，流水接着编），流水在中间时 key = 前缀#后缀
     */
    @Test
    void resolve_scopedRuleFirst_keyKeepsOldFormat() {
        rules.add(rule(2, "鞋", List.of("XZ"), text("X"), seg(CodeSegment.Type.STYLE_YEAR), seq(3), seg(CodeSegment.Type.BRAND)));

        CodeTemplate general = service.resolve(CodeTarget.STYLE, CodeContext.of("FS")
                .with(CodeSegment.Type.STYLE_CLASS, () -> "FS").with(CodeSegment.Type.DESIGNER, () -> "07"));
        assertThat(general.key()).isEqualTo("FS26110807");
        assertThat(general.format(1)).isEqualTo("FS2611080701");

        CodeTemplate shoes = service.resolve(CodeTarget.STYLE, CodeContext.of("XZ")
                .with(CodeSegment.Type.STYLE_YEAR, () -> "27").with(CodeSegment.Type.BRAND, () -> "ab")
                .with(CodeSegment.Type.DESIGNER, () -> {
                    throw new AssertionError("鞋的规则里没有设计师编号，不该去取");
                }));
        assertThat(shoes.key()).isEqualTo("X27#AB");
        assertThat(shoes.format(12)).isEqualTo("X27012AB");
        assertThat(shoes.used()).doesNotContain(CodeSegment.Type.DESIGNER);
    }

    /**
     * 规则（RFC 018 §8）：甲方 11-25 的两条规则，用现有的段加"一级分类编码"能拼出来；
     * 横杠是固定文字，算在流水的 key 里（同一个品牌、年份、设计师、品类的接着往下编）。款号的服装规则里没有大类编码，不去取
     */
    @Test
    void render_clientRules1125() {
        rules.add(rule(2, "服装", List.of("TS", "FS"), text("F"), seg(CodeSegment.Type.BRAND), seg(CodeSegment.Type.STYLE_YEAR),
                seg(CodeSegment.Type.DESIGNER), text("Z"), seg(CodeSegment.Type.CATEGORY), text("-"), seq(3)));
        CodeTemplate style = service.resolve(CodeTarget.STYLE, CodeContext.of("TS")
                .with(CodeSegment.Type.BRAND, () -> "s").with(CodeSegment.Type.STYLE_YEAR, () -> "26")
                .with(CodeSegment.Type.DESIGNER, () -> "22").with(CodeSegment.Type.CATEGORY, () -> "33")
                .with(CodeSegment.Type.STYLE_CLASS, () -> {
                    throw new AssertionError("服装规则里没有大类编码，不该去取");
                }));
        assertThat(style.key()).isEqualTo("FS2622Z33-");
        assertThat(style.format(1)).isEqualTo("FS2622Z33-001");

        CodeTemplate material = CodeRuleServiceImpl.render(CodeTarget.MATERIAL, "通用规则",
                List.of(date("yy"), seg(CodeSegment.Type.MAT_PARENT_CATEGORY), seg(CodeSegment.Type.MAT_CATEGORY), text("-"), seq(3)),
                CodeContext.of("FABRIC").with(CodeSegment.Type.MAT_PARENT_CATEGORY, () -> "ll").with(CodeSegment.Type.MAT_CATEGORY, () -> "03"),
                LocalDate.now(CLOCK));
        assertThat(material.key()).isEqualTo("26LL03-");
        assertThat(material.format(3)).isEqualTo("26LL03-003");
        assertThat(CodeSegment.Type.MAT_PARENT_CATEGORY.allowedIn(CodeTarget.STYLE)).isFalse();
    }

    /** 规则：规则要用的值没给、或者有字母数字横杠以外的字（中文品牌），编不了，报清楚 */
    @Test
    void resolve_missingOrBadValue_rejected() {
        assertThatThrownBy(() -> service.resolve(CodeTarget.STYLE, CodeContext.of("FS")
                .with(CodeSegment.Type.STYLE_CLASS, () -> "FS").with(CodeSegment.Type.DESIGNER, () -> null)))
                .isInstanceOf(BizException.class).hasMessageContaining("设计师编号");
        assertThatThrownBy(() -> service.resolve(CodeTarget.STYLE, CodeContext.of("FS")
                .with(CodeSegment.Type.STYLE_CLASS, () -> "服饰").with(CodeSegment.Type.DESIGNER, () -> "07")))
                .isInstanceOf(BizException.class).hasMessageContaining("编不进编码");
    }

    /** 规则（RFC 018 §4.6）：保存前查段：没有流水、两段流水、段不能用在这种编码、太长、只有流水，都不让存 */
    @Test
    void create_badSegments_rejected() {
        assertThatThrownBy(() -> service.create(req(CodeTarget.STYLE, List.of("XZ"), text("X"))))
                .hasMessageContaining("要有一段流水");
        assertThatThrownBy(() -> service.create(req(CodeTarget.STYLE, List.of("XZ"), text("X"), seq(2), seq(3))))
                .hasMessage("流水只能有一段");
        assertThatThrownBy(() -> service.create(req(CodeTarget.MATERIAL, List.of("FABRIC"), seg(CodeSegment.Type.DESIGNER), seq(4))))
                .hasMessage("「设计师编号」不能用在物料编码里");
        assertThatThrownBy(() -> service.create(req(CodeTarget.STYLE, List.of("XZ"), text("ABCDEFGH"), text("ABCDEFGH"),
                text("ABCDEFGH"), date("yyyy"), seq(6))))
                .hasMessageContaining("最多 32 位");
        assertThatThrownBy(() -> service.create(req(CodeTarget.STYLE, List.of("XZ"), seq(4))))
                .hasMessageContaining("只有流水不行");
        verify(ruleMapper, never()).insert(any(SysCodeRule.class));
    }

    /** 规则：一个大类只能在一条规则里；通用规则不能删 */
    @Test
    void create_scopeTakenByOtherRule_rejected_generalNotDeletable() {
        rules.add(rule(2, "鞋", List.of("XZ"), text("X"), seq(3)));

        assertThatThrownBy(() -> service.create(req(CodeTarget.STYLE, List.of("FS", "XZ"), text("Y"), seq(3))))
                .isInstanceOf(BizException.class)
                .hasMessage("大类「鞋」已经在规则「鞋」里了：一个大类只能按一条规则编");
        verify(ruleMapper, never()).insert(any(SysCodeRule.class));

        when(ruleMapper.selectById(1L)).thenReturn(rules.getFirst());
        assertThatThrownBy(() -> service.delete(1)).hasMessageContaining("通用规则不能删");
        verify(ruleMapper, never()).deleteById(any(Long.class));
    }

    /** 规则：存的时候固定文字转大写，排在已有规则后面 */
    @Test
    void create_textUppercasedAndSortedLast() {
        Long id = service.create(req(CodeTarget.STYLE, List.of("XZ"), text("xz-"), seg(CodeSegment.Type.STYLE_YEAR), seq(3)));

        ArgumentCaptor<SysCodeRule> saved = ArgumentCaptor.captor();
        verify(ruleMapper).insert(saved.capture());
        assertThat(saved.getValue().getSegments().getFirst().text()).isEqualTo("XZ-");
        assertThat(saved.getValue().getScopeValues()).containsExactly("XZ");
        assertThat(saved.getValue().getSort()).isEqualTo(1);
        verify(ruleMapper).lockGeneral("STYLE");
        assertThat(id).isNull();   // mock 的 insert 不回填 id
    }

    private static SysCodeRule rule(long id, String name, List<String> scope, CodeSegment... segments) {
        SysCodeRule r = new SysCodeRule();
        r.setId(id);
        r.setTarget("STYLE");
        r.setName(name);
        r.setScopeValues(scope);
        r.setSegments(List.of(segments));
        r.setSort((int) id - 1);
        return r;
    }

    private static CodeRuleSaveReq req(CodeTarget target, List<String> scope, CodeSegment... segments) {
        CodeRuleSaveReq req = new CodeRuleSaveReq();
        req.setTarget(target);
        req.setName("测试规则");
        req.setScopeValues(scope);
        req.setSegments(List.of(segments));
        return req;
    }

    private static CodeSegment seg(CodeSegment.Type type) {
        return new CodeSegment(type, null, null, null);
    }

    private static CodeSegment text(String text) {
        return new CodeSegment(CodeSegment.Type.TEXT, text, null, null);
    }

    private static CodeSegment date(String format) {
        return new CodeSegment(CodeSegment.Type.DATE, null, format, null);
    }

    private static CodeSegment seq(int width) {
        return new CodeSegment(CodeSegment.Type.SEQ, null, null, width);
    }
}
