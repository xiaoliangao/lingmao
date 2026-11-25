package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.OperLogDetail;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.module.system.dto.CodeContext;
import com.lingmao.scm.module.system.dto.CodeRuleSaveReq;
import com.lingmao.scm.module.system.dto.CodeRuleVO;
import com.lingmao.scm.module.system.dto.CodeSegment;
import com.lingmao.scm.module.system.dto.CodeTarget;
import com.lingmao.scm.module.system.dto.CodeTemplate;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.SysCodeRule;
import com.lingmao.scm.module.system.mapper.SysCodeRuleMapper;
import com.lingmao.scm.module.system.service.CodeRuleService;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 编码规则（RFC 018 §4）。每次编号都读一次库（一种编码几行），不缓存：管理员改了规则，下一个号就按新的编。
 * 保存规则前锁住这种编码的通用规则那一行，同一种编码的保存排队
 */
@Service
@RequiredArgsConstructor
public class CodeRuleServiceImpl implements CodeRuleService {

    /** 编码里每一段只能是这些字符：大写字母、数字、横杠 */
    static final Pattern CODE_CHARS = Pattern.compile("^[A-Z0-9-]+$");
    static final int MIN_SEQ_WIDTH = 2;
    static final int MAX_SEQ_WIDTH = 6;
    static final int MAX_TEXT = 8;

    private final SysCodeRuleMapper ruleMapper;
    private final DictDataService dictDataService;
    private final Clock clock;

    @Override
    public List<CodeRuleVO> list(CodeTarget target) {
        return rules(target).stream().map(CodeRuleServiceImpl::vo).toList();
    }

    @Override
    @Transactional
    public Long create(CodeRuleSaveReq req) {
        CodeTarget target = req.getTarget();
        ruleMapper.lockGeneral(target.name());
        List<String> scope = normalizeScope(req.getScopeValues());
        if (scope.isEmpty()) {
            throw new BizException(400, target.scopeDict() == null
                    ? target.label() + "只有一条通用规则，不能再加"
                    : "请选这条规则管哪几个" + target.scopeLabel() + "：不选的话就是通用规则，通用规则已经有了");
        }
        List<CodeSegment> segments = validate(target, req, scope, null, null);
        SysCodeRule rule = new SysCodeRule();
        rule.setTarget(target.name());
        rule.setName(req.getName().strip());
        rule.setScopeValues(scope);
        rule.setSegments(segments);
        rule.setSort(rules(target).stream().map(SysCodeRule::getSort).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0) + 1);
        ruleMapper.insert(rule);
        OperLogDetail.put("after", vo(rule));
        return rule.getId();
    }

    @Override
    @Transactional
    public void update(long id, CodeRuleSaveReq req) {
        CodeTarget target = req.getTarget();
        ruleMapper.lockGeneral(target.name());
        SysCodeRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(404, "编码规则不存在: " + id);
        }
        if (!rule.getTarget().equals(target.name())) {
            throw new BizException(400, "规则的编码种类不能改");
        }
        OperLogDetail.put("before", vo(rule));
        boolean general = rule.getScopeValues().isEmpty();
        List<String> scope = normalizeScope(req.getScopeValues());
        if (general && !scope.isEmpty()) {
            throw new BizException(400, "通用规则管所有没单独定规则的，不能选" + target.scopeLabel());
        }
        if (!general && scope.isEmpty()) {
            throw new BizException(400, "请选这条规则管哪几个" + target.scopeLabel() + "；不想要这条规则就删掉它");
        }
        rule.setSegments(validate(target, req, scope, rule.getScopeValues(), id));
        rule.setName(req.getName().strip());
        rule.setScopeValues(scope);
        ruleMapper.update(rule, Updates.replaceById(rule));
        OperLogDetail.put("after", vo(rule));
    }

    @Override
    @Transactional
    public void delete(long id) {
        SysCodeRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw new BizException(404, "编码规则不存在: " + id);
        }
        if (rule.getScopeValues().isEmpty()) {
            throw new BizException("通用规则不能删：没单独定规则的都按它编");
        }
        OperLogDetail.put("before", vo(rule));
        ruleMapper.deleteById(id);
    }

    @Override
    public CodeTemplate resolve(CodeTarget target, CodeContext context) {
        List<SysCodeRule> rules = rules(target);
        SysCodeRule rule = rules.stream()
                .filter(r -> context.scopeValue() != null && r.getScopeValues().contains(context.scopeValue()))
                .findFirst()
                .orElseGet(() -> rules.stream().filter(r -> r.getScopeValues().isEmpty()).findFirst()
                        .orElseThrow(() -> new BizException(target.label() + "的通用规则不见了，请超级管理员在 管理后台 › 编码规则 里看一下")));
        return render(target, rule.getName(), rule.getSegments(), context, LocalDate.now(clock));
    }

    /**
     * 按一条规则的段拼成只差流水的样子。单测里没有库，拿它按预置规则拼（TestCodeRules）
     *
     * @param ruleName 报错时说是哪条规则
     */
    public static CodeTemplate render(CodeTarget target, String ruleName, List<CodeSegment> segments, CodeContext context,
                                      LocalDate today) {
        StringBuilder prefix = new StringBuilder();
        StringBuilder suffix = new StringBuilder();
        Integer width = null;
        Set<CodeSegment.Type> used = EnumSet.noneOf(CodeSegment.Type.class);
        for (CodeSegment segment : segments) {
            used.add(segment.type());
            if (segment.type() == CodeSegment.Type.SEQ) {
                width = segment.width();
                continue;
            }
            String value = switch (segment.type()) {
                case TEXT -> segment.text();
                case DATE -> today.format(DateTimeFormatter.ofPattern(segment.format()));
                default -> valueOf(target, ruleName, segment.type(), context);
            };
            (width == null ? prefix : suffix).append(value);
        }
        if (width == null) {
            throw new BizException("编码规则「" + ruleName + "」没有流水，请超级管理员在 管理后台 › 编码规则 里改");
        }
        CodeTemplate template = new CodeTemplate(prefix.toString(), suffix.toString(), width, ruleName, used);
        if (template.format(1).length() > CodeTarget.MAX_LENGTH || template.key().length() > CodeTarget.MAX_LENGTH) {
            throw new BizException("按编码规则「" + ruleName + "」编出来超过 " + CodeTarget.MAX_LENGTH + " 位了（" + template.format(1)
                    + "），请超级管理员缩短规则，或者手填");
        }
        return template;
    }

    /** 大类、品牌、设计师编号……这一段的值：转大写，只能是字母、数字、横杠 */
    private static String valueOf(CodeTarget target, String ruleName, CodeSegment.Type type, CodeContext context) {
        Supplier<String> supplier = context.supplier(type);
        String raw = supplier == null ? null : supplier.get();
        if (raw == null || raw.isBlank()) {
            throw new BizException("编码规则「" + ruleName + "」里有「" + type.label() + "」，这" + noun(target) + "还没有"
                    + type.label() + "：先填上，或者手填编码");
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        if (!CODE_CHARS.matcher(value).matches()) {
            throw new BizException(type.label() + "「" + raw + "」里有字母、数字、横杠以外的字，编不进编码：先在字典或资料里改成字母数字，或者手填编码");
        }
        return value;
    }

    private static String noun(CodeTarget target) {
        return switch (target) {
            case STYLE -> "个款式";
            case MATERIAL -> "个物料";
            case SUPPLIER -> "家供应商";
        };
    }

    /**
     * 保存前的检查（RFC 018 §4.6）：段都能用在这种编码上、各段的参数对、流水有且只有一个、最长不超过 32 位；
     * 按什么分的值在字典里、没在别的规则里。返回规整过的段（固定文字转大写）
     */
    private List<CodeSegment> validate(CodeTarget target, CodeRuleSaveReq req, List<String> scope, List<String> previousScope,
                                       Long selfId) {
        List<CodeSegment> segments = new ArrayList<>();
        int seqCount = 0;
        int maxLength = 0;
        int seqWidth = 0;
        for (CodeSegment s : req.getSegments()) {
            if (!s.type().allowedIn(target)) {
                throw new BizException(400, "「" + s.type().label() + "」不能用在" + target.label() + "里");
            }
            CodeSegment clean = switch (s.type()) {
                case TEXT -> {
                    String text = s.text() == null ? "" : s.text().strip().toUpperCase(Locale.ROOT);
                    if (text.isEmpty() || text.length() > MAX_TEXT || !CODE_CHARS.matcher(text).matches()) {
                        throw new BizException(400, "固定文字是 1-" + MAX_TEXT + " 位大写字母、数字、横杠");
                    }
                    yield new CodeSegment(s.type(), text, null, null);
                }
                case DATE -> {
                    if (!CodeSegment.DATE_FORMATS.contains(s.format())) {
                        throw new BizException(400, "日期的格式只能选 yy、yyMM、yyMMdd、yyyy");
                    }
                    yield new CodeSegment(s.type(), null, s.format(), null);
                }
                case SEQ -> {
                    if (s.width() == null || s.width() < MIN_SEQ_WIDTH || s.width() > MAX_SEQ_WIDTH) {
                        throw new BizException(400, "流水是 " + MIN_SEQ_WIDTH + "-" + MAX_SEQ_WIDTH + " 位");
                    }
                    seqCount++;
                    seqWidth = s.width();
                    yield new CodeSegment(s.type(), null, null, s.width());
                }
                default -> new CodeSegment(s.type(), null, null, null);
            };
            maxLength += clean.maxLength();
            segments.add(clean);
        }
        if (seqCount != 1) {
            throw new BizException(400, seqCount == 0 ? "要有一段流水：没有流水，同一天同一类只能编出一个号" : "流水只能有一段");
        }
        if (maxLength > CodeTarget.MAX_LENGTH) {
            throw new BizException(400, "这条规则最长能到 " + maxLength + " 位，" + target.label() + "最多 " + CodeTarget.MAX_LENGTH
                    + " 位：去掉几段，或者少几位流水");
        }
        if (maxLength - seqWidth == 0) {
            throw new BizException(400, "只有流水不行：至少再加一段固定文字，不然和别的编码分不开");
        }
        if (!scope.isEmpty()) {
            dictDataService.requireValues(target.scopeDict(), scope, previousScope, target.scopeLabel());
            Map<String, String> labels = labels(target);
            for (SysCodeRule other : rules(target)) {
                if (Objects.equals(other.getId(), selfId)) {
                    continue;
                }
                for (String value : scope) {
                    if (other.getScopeValues().contains(value)) {
                        throw new BizException(400, target.scopeLabel() + "「" + labels.getOrDefault(value, value)
                                + "」已经在规则「" + other.getName() + "」里了：一个" + target.scopeLabel() + "只能按一条规则编");
                    }
                }
            }
        }
        return segments;
    }

    private Map<String, String> labels(CodeTarget target) {
        Map<String, String> labels = new HashMap<>();
        for (DictOption o : dictDataService.listByType(target.scopeDict())) {
            labels.put(o.value(), o.label());
        }
        return labels;
    }

    private static List<String> normalizeScope(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(values.stream().filter(Objects::nonNull).map(String::strip)
                .filter(v -> !v.isEmpty()).toList()));
    }

    /** 这种编码的规则：通用规则在最前，别的按排序、id */
    private List<SysCodeRule> rules(CodeTarget target) {
        List<SysCodeRule> rules = new ArrayList<>(ruleMapper.selectList(new LambdaQueryWrapper<SysCodeRule>()
                .eq(SysCodeRule::getTarget, target.name())));
        rules.sort(Comparator.comparing((SysCodeRule r) -> !r.getScopeValues().isEmpty())
                .thenComparing(SysCodeRule::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SysCodeRule::getId));
        return rules;
    }

    private static CodeRuleVO vo(SysCodeRule r) {
        return new CodeRuleVO(r.getId(), CodeTarget.valueOf(r.getTarget()), r.getName(), r.getScopeValues().isEmpty(),
                r.getScopeValues(), r.getSegments(), r.getSort(), r.getUpdateTime());
    }
}
