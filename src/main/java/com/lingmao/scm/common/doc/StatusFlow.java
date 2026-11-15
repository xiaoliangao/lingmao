package com.lingmao.scm.common.doc;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 状态转换表（架构 §4.2）：一个动作一行，写清从哪些状态能做、做完到什么状态。状态规则只写在这张表里，Service 里不散写 if。
 * 一种状态枚举配一张表，放在用它的 ServiceImpl 里：
 * <pre>
 * private static final StatusFlow&lt;SupplierStatus&gt; FLOW = StatusFlow.of(SupplierStatus.class)
 *         .on("submit", PENDING_ADMIT).to(ADMITTING)
 *         .on("audit", ADMITTING).to(ACTIVE)
 *         .on("blacklist", PENDING_ADMIT, ADMITTING, ACTIVE, DISABLED).to(BLACKLISTED)
 *         .on("unblacklist", BLACKLISTED).to(DISABLED, PENDING_ADMIT)   // 两个去向，由业务定（RFC 004 §2）
 *         .build();
 *
 * SupplierStatus to = FLOW.next("submit", supplier.getStatus());        // 当前状态做不了这个动作 → 1002
 * SupplierStatus back = FLOW.next("unblacklist", s.getStatus(), admitted ? DISABLED : PENDING_ADMIT);
 * List&lt;String&gt; actions = FLOW.actions(supplier.getStatus());         // 详情接口的 actions，再和权限、业务条件取交集
 * </pre>
 * 当前状态做不了这个动作报 1002，不是 1000：页面上的按钮是按打开时的状态显示的，点了却不让做，说明这期间状态被别人改了，刷新就好
 * （RFC 001 §13）。动作名不在表里（拼错了）是程序的错，抛 IllegalArgumentException，第一次跑测试就会发现。
 * <p>
 * 动作用字符串（submit、audit……）不用枚举：它原样进操作记录的 action 列（biz_doc_log，最长 16 个字符）、详情接口的 actions、
 * 权限码的最后一段，到处都是字符串；各种状态的动作又各不相同，一个公共的枚举装不下。
 * <p>
 * 查表只是第一道：查的时候状态对，写库的时候可能已经被别人改了，所以改状态还要比较并交换（StatusCas）。
 * 表建好以后不再变，可以放在 static final 里多个线程一起用。
 */
public final class StatusFlow<S extends Enum<S>> {

    /** 状态变了的提示，StatusCas 也用它：两处都是"按钮是按旧状态显示的" */
    static final String CHANGED = "状态已变化，请刷新后再试";

    /** 动作 → 这一行。按写的顺序放，actions() 也按这个顺序出，前端的按钮顺序就是表里的顺序 */
    private final Map<String, Rule<S>> rules;

    private record Rule<S extends Enum<S>>(Set<S> from, List<S> to) {
    }

    private StatusFlow(Map<String, Rule<S>> rules) {
        this.rules = Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    /** 开始写一张表。参数只用来让编译器认出是哪种状态 */
    public static <S extends Enum<S>> Builder<S> of(Class<S> statusType) {
        return new Builder<>();
    }

    /** 在 current 状态下做 action，返回做完的状态。不允许 → 1002。去向不止一个的动作用下面那个方法 */
    public S next(String action, S current) {
        Rule<S> rule = rule(action);
        if (rule.to().size() > 1) {
            throw new IllegalStateException("动作 " + action + " 有几个去向 " + rule.to() + "，用 next(action, current, target) 指定去哪个");
        }
        ensureAllowed(rule, current);
        return rule.to().getFirst();
    }

    /**
     * 去向不止一个的动作（解除黑名单回停用还是待准入、取消结案按累计数回到哪一步）：去哪个由业务算，这里查能不能做、能不能去。
     * target 不是表里写的去向是程序的错，抛 IllegalArgumentException
     */
    public S next(String action, S current, S target) {
        Rule<S> rule = rule(action);
        if (!rule.to().contains(target)) {
            throw new IllegalArgumentException("动作 " + action + " 只能到 " + rule.to() + "，不能到 " + target);
        }
        ensureAllowed(rule, current);
        return target;
    }

    /** current 状态下能不能做 action（不抛异常，算按钮用） */
    public boolean allows(String action, S current) {
        return rule(action).from().contains(current);
    }

    /** current 状态下能做的全部动作，按表里的顺序。详情接口的 actions = 这个 ∩ 用户有权限的 ∩ 业务条件（架构 §4.2） */
    public List<String> actions(S current) {
        return rules.entrySet().stream()
                .filter(e -> e.getValue().from().contains(current))
                .map(Map.Entry::getKey)
                .toList();
    }

    private Rule<S> rule(String action) {
        Rule<S> rule = rules.get(action);
        if (rule == null) {
            throw new IllegalArgumentException("转换表里没有动作 " + action + "，有的是 " + rules.keySet());
        }
        return rule;
    }

    /** current 是 null（数据坏了）也算不允许：EnumSet.contains(null) 是 false */
    private static <S extends Enum<S>> void ensureAllowed(Rule<S> rule, S current) {
        if (!rule.from().contains(current)) {
            throw new BizException(ErrorCodes.STATE_CHANGED, CHANGED);
        }
    }

    /** 一行一行地写：on(动作, 从哪些状态).to(到哪些状态)，最后 build() */
    public static final class Builder<S extends Enum<S>> {

        private final Map<String, Rule<S>> rules = new LinkedHashMap<>();

        private Builder() {
        }

        @SafeVarargs
        public final Step on(String action, S from, S... moreFrom) {
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException("动作名不能为空");
            }
            if (rules.containsKey(action)) {
                throw new IllegalStateException("动作 " + action + " 写了两行：一个动作只能有一行，几个起点写在同一个 on 里");
            }
            return new Step(action, EnumSet.of(from, moreFrom));
        }

        public StatusFlow<S> build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("转换表是空的");
            }
            return new StatusFlow<>(rules);
        }

        /** on(...) 写了一半的一行，接 to(...) 写完 */
        public final class Step {

            private final String action;
            private final Set<S> from;

            private Step(String action, Set<S> from) {
                this.action = action;
                this.from = from;
            }

            @SafeVarargs
            public final Builder<S> to(S target, S... moreTargets) {
                List<S> to = Stream.concat(Stream.of(target), Arrays.stream(moreTargets))
                        .map(Objects::requireNonNull).distinct().toList();
                rules.put(action, new Rule<>(Collections.unmodifiableSet(from), to));
                return Builder.this;
            }
        }
    }
}
