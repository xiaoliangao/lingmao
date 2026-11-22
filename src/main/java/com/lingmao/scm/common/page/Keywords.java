package com.lingmao.scm.common.page;

import com.lingmao.scm.common.exception.BizException;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 列表关键字里一次查几个单号、编码（PRD §5.4，docs/api/README §3.2）。
 * 只有一个词时，编码、单号按包含查（upper(编码列) LIKE '%词%'，走 pg_trgm 索引）。
 * 关键字里有分隔符（空白、逗号、顿号、分号）就拆开：大写、去重，SQL 里 upper(编码列) IN (...) 精确查；
 * 整句照样按包含查名称这些（名称里本来就可能有空格、逗号），两个条件 OR 起来。
 * 前端的"批量"框一行一个，拼成空格隔开的发过来；从 Excel 复制一列直接粘进搜索框，换行浏览器也会变成空格。
 *
 *   IPage<Row> page = mapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()));
 *
 *   <if test="q.keyword != null and q.keyword.trim() != ''">
 *       <bind name="keywordCode" value="@com.lingmao.scm.common.page.Keywords@codeContains(q.keyword)"/>
 *       AND (upper(d.no) LIKE #{keywordCode} OR …
 *            <if test="codes != null">OR upper(d.no) IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach></if>)
 *   </if>
 */
public final class Keywords {

    /** 一次最多查几个：再多 GET 的地址就太长了（Nginx、Tomcat 默认 8KB）。前端 BatchKeyword.vue 同一个数 */
    public static final int MAX_CODES = 100;
    /** 整个关键字也须有界；去重前的重复输入不能无限占用内存。 */
    public static final int MAX_LENGTH = 8_192;

    // 两端采用相同空白集合：Unicode White_Space 加 BOM（浏览器 \s 包含 BOM，但不含 NEL）。
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\uFEFF,，、;；]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern OUTER_SPACE = Pattern.compile("^[\\s\\uFEFF]+|[\\s\\uFEFF]+$", Pattern.UNICODE_CHARACTER_CLASS);

    private Keywords() {
    }

    /** 拆出来的编码（大写、去重、按原来的顺序）；没有分隔符（只是一个词）返回 null，编码按包含查。超过 MAX_CODES 个回 400 */
    public static List<String> codes(String keyword) {
        if (keyword == null) {
            return null;
        }
        if (keyword.length() > MAX_LENGTH) throw new BizException(400, "查询内容过长，请分批查询");
        String text = strip(keyword);
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

    /** 单个编码按包含查，和 upper(编码列) LIKE 配对；批量编码返回 null，只走调用点的精确 IN。百分号、下划线、反斜杠只是文字 */
    public static String codeContains(String keyword) {
        if (keyword == null) return null;
        if (codes(keyword) != null) return null;
        String text = strip(keyword);
        return text.isEmpty() ? null : "%" + escapeLike(text.toUpperCase(Locale.ROOT)) + "%";
    }

    /** 名称仍可按完整短语包含查，但用户输入的百分号、下划线和反斜杠都只是文字。 */
    public static String contains(String keyword) {
        return contains(keyword, MAX_LENGTH);
    }

    /** 有界 VARCHAR 放不下完整短语时不可能命中；批量长编码不再触发无意义的名称全文扫描。 */
    public static String contains(String keyword, int maxCharacters) {
        if (keyword == null) return null;
        codes(keyword);
        String text = strip(keyword);
        if (text.isEmpty()) return null;
        if (text.codePointCount(0, text.length()) > maxCharacters) return null;
        return "%" + escapeLike(text) + "%";
    }

    /** 历史 CSV 编码的 GIN 预筛选；调用点还须保留数组精确匹配，不能用这些包含模式代替精确语义。 */
    public static List<CodePattern> batchContains(String keyword) {
        List<String> codes = codes(keyword);
        return codes == null ? List.of() : codes.stream().map(code -> new CodePattern(code, "%" + escapeLike(code) + "%")).toList();
    }

    public record CodePattern(String code, String contains) { }

    private static String strip(String text) {
        return OUTER_SPACE.matcher(text).replaceAll("");
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
