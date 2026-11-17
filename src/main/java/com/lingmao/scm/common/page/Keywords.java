package com.lingmao.scm.common.page;

import com.lingmao.scm.common.exception.BizException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 列表关键字里一次查几个单号、编码（PRD §5.4，docs/api/README §3.2）。
 * 关键字里有分隔符（空白、逗号、顿号、分号）就拆开：大写、去重，SQL 里 upper(编码列) IN (...) 精确查；
 * 整句照样按包含查名称这些（名称里本来就可能有空格、逗号），两个条件 OR 起来。
 * 前端的"批量"框一行一个，拼成空格隔开的发过来；从 Excel 复制一列直接粘进搜索框，换行浏览器也会变成空格。
 *
 *   IPage<Row> page = mapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
 *
 *   <if test="q.keyword != null and q.keyword.trim() != ''">
 *       AND (d.no ILIKE '%' || #{q.keyword} || '%' OR …
 *            <if test="codes != null">OR upper(d.no) IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach></if>)
 *   </if>
 */
public final class Keywords {

    /** 一次最多查几个：再多 GET 的地址就太长了（Nginx、Tomcat 默认 8KB）。前端 BatchKeyword.vue 同一个数 */
    public static final int MAX_CODES = 200;

    private static final Pattern SEPARATORS = Pattern.compile("[\\s,，、;；]+");

    private Keywords() {
    }

    /** 拆出来的编码（大写、去重、按原来的顺序）；没有分隔符（只是一个词）返回 null，只按包含查。超过 MAX_CODES 个回 400 */
    public static List<String> codes(String keyword) {
        if (keyword == null) {
            return null;
        }
        String text = keyword.strip();
        if (!SEPARATORS.matcher(text).find()) {
            return null;
        }
        List<String> codes = SEPARATORS.splitAsStream(text)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (codes.size() > MAX_CODES) {
            throw new BizException(400, "一次最多查 " + MAX_CODES + " 个，现在 " + codes.size() + " 个，分几次查");
        }
        return codes.isEmpty() ? null : codes;
    }
}
