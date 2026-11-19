package com.lingmao.scm.security;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.lingmao.scm.common.exception.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 品类线隔离（RFC 013 §2，甲方 11-18 答 Q13、Q30）：服装、鞋类、百货互相看不见。人归一条或几条线（sys_user.product_lines），
 * 款式、需求订单、算料单、采购单、生产单各属一条线（product_line），供应商可以做几条线（sup_supplier.product_lines）。
 * 和数据范围（{@link DataScope}，全部 / 本人）是两道，叠在一起：鞋类的采购员只看鞋类里自己的单。
 * <p>
 * 加载权限时多给几个权限标识 line:APPAREL……，超级管理员给 line:all（PermissionServiceImpl），这里只读当前请求的权限，不另外查库。
 * 写法照 DataScope，显式加条件：
 * <pre>
 * LineScope lines = LineScope.current();
 * lines.apply(wrapper, Style::getProductLine);       // product_line IN (我的几条)；看全部时不加
 * lines.applyAny(wrapper, "product_lines");          // 供应商：product_lines 和我的几条有交集
 * if (!lines.canSee(style.getProductLine())) …       // 详情、动作：不在我的线里 → 404
 * </pre>
 * 单据的 Mapper XML 已经收了 DataScope，从它取（scope.lines），不用另加参数：
 * <pre>
 * &lt;if test="scope.lines.limited"&gt;
 *   AND o.product_line = ANY(#{scope.lines.values, typeHandler=com.lingmao.scm.common.mybatis.StringArrayTypeHandler})
 * &lt;/if&gt;
 * </pre>
 * 用 = ANY(数组) 不用 IN (foreach)：一条线都没有（没登录）时 IN () 是语法错误，= ANY('{}') 就是什么都看不到。
 * <p>
 * 没登录算"一条线都没有"，什么都看不到。定时任务、导入导出的后台线程不经过这里（它们不查列表）。
 * 一个人的线覆盖了字典里全部的线也按"有几条"查，不当成看全部：以后加了第四条线，他不会自动看到
 */
public final class LineScope {

    /** 字典类型：品类线（V44，三项内置 APPAREL / FOOTWEAR / GENERAL） */
    public static final String DICT_TYPE = "product_line";

    /** 权限标识前缀：line:APPAREL */
    public static final String AUTHORITY_PREFIX = "line:";

    /** 超级管理员多出的这个：看全部线 */
    public static final String ALL_AUTHORITY = "line:all";

    /** 服装：看全部线的人新建还定不了线的单据时用它 */
    public static final String APPAREL = "APPAREL";

    private static final LineScope ALL = new LineScope(null);

    /** 能看的线；null = 全部 */
    private final List<String> values;

    private LineScope(List<String> values) {
        this.values = values;
    }

    /** 当前登录用户能看的线 */
    public static LineScope current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof LoginUser)) {
            return new LineScope(List.of());
        }
        List<String> lines = new ArrayList<>();
        for (GrantedAuthority granted : auth.getAuthorities()) {
            String a = granted.getAuthority();
            if (ALL_AUTHORITY.equals(a)) {
                return ALL;
            }
            if (a.startsWith(AUTHORITY_PREFIX)) {
                lines.add(a.substring(AUTHORITY_PREFIX.length()));
            }
        }
        return new LineScope(List.copyOf(lines));
    }

    /** 看全部线（测试、不分线的内部调用） */
    public static LineScope all() {
        return ALL;
    }

    /** 只看这几条（测试用） */
    public static LineScope of(Collection<String> lines) {
        return new LineScope(List.copyOf(lines));
    }

    /** 加载权限时给的标识：每条线一个 line:XXX */
    public static List<String> authorities(Collection<String> lines) {
        return lines.stream().map(l -> AUTHORITY_PREFIX + l).toList();
    }

    /** true = 只看几条线，要加条件；XML 里 &lt;if test="scope.lines.limited"&gt; */
    public boolean isLimited() {
        return values != null;
    }

    /** 能看的线；看全部时是空的（先看 isLimited） */
    public List<String> getValues() {
        return values == null ? List.of() : values;
    }

    /** 新建时还定不了线的（没有明细的订单草稿）：只看几条的取第一条（字典顺序），看全部的取服装 */
    public String first() {
        return values == null || values.isEmpty() ? APPAREL : values.getFirst();
    }

    /** 只有一条线时是它（手工建采购单自动带上），否则 null */
    public String only() {
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    /** 这条线我能不能看；null（还没定线）只有看全部的人能看 */
    public boolean canSee(String line) {
        return values == null || line != null && values.contains(line);
    }

    /** 这几条线里有没有我能看的（供应商） */
    public boolean canSeeAny(Collection<String> lines) {
        return values == null || lines != null && lines.stream().anyMatch(this::canSee);
    }

    /** 单据详情、动作前查：不在我的线里抛 404，和"不存在"一样不区分（同 DataScope.check） */
    public void check(String line) {
        if (!canSee(line)) {
            throw new BizException(404, "单据不存在");
        }
    }

    /** 给 LambdaQueryWrapper 加 列 IN (我的几条)；看全部时不动 */
    public <T> LambdaQueryWrapper<T> apply(LambdaQueryWrapper<T> wrapper, SFunction<T, ?> column) {
        if (values == null) {
            return wrapper;
        }
        return values.isEmpty() ? wrapper.apply("1 = 0") : wrapper.in(column, values);
    }

    /** 给查询加 数组列 &amp;&amp; 我的几条（有交集）；看全部时不动。column 是列名，不是用户输入 */
    public <C extends AbstractWrapper<?, ?, C>> C applyAny(C wrapper, String column) {
        if (values == null) {
            return wrapper;
        }
        if (values.isEmpty()) {
            return wrapper.apply("1 = 0");
        }
        String placeholders = IntStream.range(0, values.size()).mapToObj(i -> "{" + i + "}").collect(Collectors.joining(","));
        return wrapper.apply(column + " && ARRAY[" + placeholders + "]::varchar[]", values.toArray());
    }
}
