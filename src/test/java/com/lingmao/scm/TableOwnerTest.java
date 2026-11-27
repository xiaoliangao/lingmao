package com.lingmao.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.lingmao.scm.module.biz.mapper.AttachmentMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 表的主人（架构 §3.2 规则 3、6，§13）：每张表只有一个域能写。ArchUnit 看不到 SQL 里写的是哪张表，这里扫 src/main 里各域 Mapper 的 SQL：
 * <ol>
 *   <li>注解（@Select、@Insert、@Update、@Delete）里的 SQL，和 resources/mapper 下 XML 里的语句：INSERT INTO、UPDATE、DELETE FROM、
 *       MERGE INTO、TRUNCATE 后面的表，前缀必须是本域的（PREFIXES，照架构 §3.1）。@Select 里也查：WITH ... DELETE ... RETURNING 也是写</li>
 *   <li>继承 BaseMapper&lt;实体&gt; 的：BaseMapper 自带增删改，实体 @TableName 的前缀必须是本域的</li>
 *   <li>board 只读：上面两种一个都不能有</li>
 *   <li>扫不出来的算违规：@XxxProvider（SQL 运行时才拼出来）、${} 拼的表名、include 别的文件里的片段</li>
 * </ol>
 * 只管写，不管读：规则 4 允许列表在自己的 Mapper XML 里 JOIN 别的域的表取名称、编码，这类 SELECT 扫不出"是不是只取了显示字段"，
 * 由审代码看。SELECT ... FOR UPDATE 锁行也不算写。
 * <p>
 * 查不到的漏洞：Mapper 以外的地方写 SQL（JdbcTemplate）。现在只有 common 的 SerialNoGenerator（sys_serial）和 TableComments（只读），
 * module 里一处都没有；以后谁在 module 里用 JdbcTemplate 写表，这个测试看不见。
 */
class TableOwnerTest {

    private static final String MODULE = "com.lingmao.scm.module.";

    /** 每个域能写哪些前缀的表，照架构 §3.1 抄，那张表改了这里跟着改。board 只读，一个都没有 */
    static final Map<String, List<String>> PREFIXES = Map.ofEntries(
            entry("system", List.of("sys_")),
            entry("biz", List.of("biz_")),
            entry("basic", List.of("bas_")),
            entry("supplier", List.of("sup_")),
            entry("price", List.of("prc_")),
            entry("style", List.of("sty_")),
            entry("techpack", List.of("tp_")),
            entry("order", List.of("ord_")),
            entry("inventory", List.of("inv_")),
            entry("purchase", List.of("mrp_", "pur_")),
            entry("production", List.of("prd_")),
            entry("contract", List.of("ctr_")),
            entry("dev", List.of("dev_")),
            entry("fg", List.of("fg_")),
            entry("settle", List.of("stl_")),
            entry("quality", List.of("qc_")),
            entry("board", List.of()));

    /** Mapper XML 放在这里（application.yaml 的 mybatis-plus.mapper-locations）。测试的工作目录是项目根目录 */
    private static final Path XML_DIR = Path.of("src/main/resources/mapper");

    private static final List<Class<? extends Annotation>> PROVIDERS =
            List.of(SelectProvider.class, InsertProvider.class, UpdateProvider.class, DeleteProvider.class);

    /** 写一张表的几种写法，第 2 组是表名。表名先宽松地取到空白、括号、逗号为止，再看它是不是一个固定的名字 */
    private static final Pattern WRITE = Pattern.compile(
            "\\b(INSERT\\s+INTO|DELETE\\s+FROM|MERGE\\s+INTO|TRUNCATE(?:\\s+TABLE)?|UPDATE)\\s+(?:ONLY\\s+)?([^\\s(),;]+)",
            Pattern.CASE_INSENSITIVE);

    /** 前面是这些词的 UPDATE 不是"改一张表"：ON CONFLICT DO UPDATE、FOR UPDATE、FOR NO KEY UPDATE、MERGE 的 THEN UPDATE */
    private static final Set<String> NOT_A_TABLE_UPDATE = Set.of("do", "for", "key", "then");

    private static final Pattern LAST_WORD = Pattern.compile("(\\w+)\\s*$");

    /** 字符串、行注释、块注释：里面的 UPDATE、DELETE FROM 不是语句 */
    private static final Pattern LITERAL_OR_COMMENT = Pattern.compile("'(?:[^']|'')*'|--[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    /** 带 schema 的表名 public.pur_order：只看表名 */
    private static final Pattern SCHEMA_TABLE = Pattern.compile("\\w+\\.(\\w+)");

    /** 规则：src/main 里每个 Mapper（注解、XML、BaseMapper 的实体）只写本域的表，board 什么都不写 */
    @Test
    void mappersWriteOnlyOwnTables() throws Exception {
        List<Class<?>> mappers = mainMappers();
        List<String> violations = new ArrayList<>();
        for (Class<?> mapper : mappers) {
            violations.addAll(check(mapper));
        }
        for (Path xml : xmlFiles()) {
            violations.addAll(checkXml(XML_DIR.relativize(xml).toString(), Files.readString(xml)));
        }

        assertThat(mappers).as("扫描没找到 Mapper，检查扫描本身").contains(SysUserMapper.class, AttachmentMapper.class);
        assertThat(violations).as("这些 Mapper 写了不归自己的表：每张表只有主人域能写，别的域调它的 Service（架构 §3.2）").isEmpty();
    }

    /** 检查本身要靠得住：每种违规都抓得住，报的是哪个方法、哪张表 */
    @Test
    void check_catchesEveryKindOfViolation() {
        assertThat(check("style", StyleWritesSupplier.class)).singleElement().asString()
                .contains("StyleWritesSupplier.blacklist()").contains("sup_supplier");
        assertThat(check("style", StyleHidesWriteInSelect.class)).singleElement().asString()
                .contains("sup_supplier_cert");
        assertThat(check("style", StyleDynamicTable.class)).singleElement().asString()
                .contains("${table}").contains("不是固定的");
        assertThat(check("style", StyleProvider.class)).singleElement().asString()
                .contains("@UpdateProvider");
        assertThat(check("style", StyleBaseMapperOfSupplier.class)).singleElement().asString()
                .contains("BaseMapper<SupplierRow>").contains("sup_supplier");
        assertThat(check("style", StyleBaseMapperNoTableName.class)).singleElement().asString()
                .contains("style_color_row");
        assertThat(check("board", BoardWrites.class)).singleElement().asString()
                .contains("board 只读").contains("prd_report");
        assertThat(check("board", BoardBaseMapper.class)).singleElement().asString()
                .contains("board 只读").contains("sty_style");
        assertThat(check("crm", PurchaseAllowed.class)).isNotEmpty()
                .allSatisfy(v -> assertThat(v).contains("不在 TableOwnerTest.PREFIXES 里"));
        assertThat(check(StyleWritesSupplier.class)).singleElement().asString()
                .contains("module.<域>");
    }

    /** 不能误报：本域的两个前缀、ON CONFLICT DO UPDATE、FOR UPDATE、读别的域的表、注释和字符串里的字、update_time 这种列名、<script> */
    @Test
    void check_allowsOwnWritesAndReads() {
        assertThat(check("purchase", PurchaseAllowed.class)).isEmpty();
        assertThat(check("style", StyleBaseMapperOfOwnTable.class)).isEmpty();
    }

    /** XML 也扫：include 本文件的片段会展开再查；include 别的文件的片段查不了，算违规；JOIN 别的域的表只读，不算 */
    @Test
    void checkXml_catchesViolations() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.lingmao.scm.module.style.mapper.StyleMapper">
                    <sql id="supplierTable">sup_supplier</sql>
                    <select id="page">
                        SELECT s.code, p.name AS supplier_name FROM sty_style s LEFT JOIN sup_supplier p ON p.id = s.supplier_id
                        <where><if test="code != null">AND s.code = #{code}</if></where>
                    </select>
                    <delete id="deleteColors">DELETE FROM sty_style_color WHERE style_id = #{id}</delete>
                    <update id="touchSupplier">UPDATE <include refid="supplierTable"/> <set>name = #{name}</set> WHERE id = #{id}</update>
                    <insert id="copy">INSERT INTO <include refid="com.lingmao.scm.module.basic.mapper.X.table"/> (id) VALUES (1)</insert>
                </mapper>
                """;

        List<String> violations = checkXml("style/StyleMapper.xml", xml);

        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("touchSupplier").contains("sup_supplier");
        assertThat(violations.get(1)).contains("copy").contains("不是固定的");
    }

    // ---------------------------------------------------------------- 检查

    /** src/main 里的 Mapper：标了 @Mapper，或者继承了 MyBatis-Plus 的 Mapper。用 ArchUnit 导入，不含测试里的类 */
    private static List<Class<?>> mainMappers() {
        return new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.lingmao.scm").stream()
                .filter(JavaClass::isInterface)
                .filter(c -> c.isAnnotatedWith(Mapper.class) || c.isAssignableTo(com.baomidou.mybatisplus.core.mapper.Mapper.class))
                .<Class<?>>map(JavaClass::reflect)
                .toList();
    }

    private static List<Path> xmlFiles() throws Exception {
        if (!Files.isDirectory(XML_DIR)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(XML_DIR)) {
            return files.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
    }

    /** 一个 Mapper 接口：它属于哪个域，看包名 */
    static List<String> check(Class<?> mapper) {
        String domain = domainOf(mapper.getName());
        if (domain == null) {
            return List.of(mapper.getName() + "：Mapper 要放在 com.lingmao.scm.module.<域> 下面，不然不知道它是哪个域的");
        }
        return check(domain, mapper);
    }

    /** 把 mapper 当成 domain 域的来查（测试里的反例不在 module 包下，直接指定域） */
    static List<String> check(String domain, Class<?> mapper) {
        List<String> violations = new ArrayList<>();
        Class<?> entity = ResolvableType.forClass(mapper).as(BaseMapper.class).getGeneric(0).resolve();
        if (entity != null) {
            violations.addAll(checkWrite(domain,
                    mapper.getSimpleName() + " 继承的 BaseMapper<" + entity.getSimpleName() + ">（自带增删改）", tableOf(entity)));
        }
        for (Method method : mapper.getMethods()) {
            // BaseMapper 自己的方法没有 SQL 注解，只看我们写的
            if (!method.getDeclaringClass().getName().startsWith("com.lingmao.scm")) {
                continue;
            }
            String where = mapper.getSimpleName() + "." + method.getName() + "()";
            for (Class<? extends Annotation> provider : PROVIDERS) {
                if (method.isAnnotationPresent(provider)) {
                    violations.add(where + "：用了 @" + provider.getSimpleName() + "，SQL 运行时才拼出来，扫不到写了哪张表；改成 @Select 这类注解或 XML");
                }
            }
            String sql = sqlOf(method);
            if (sql != null) {
                violations.addAll(checkSql(domain, where, sql));
            }
        }
        return violations;
    }

    /** 一个 Mapper XML：域看 namespace（对应的 Mapper 接口的全名） */
    static List<String> checkXml(String source, String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 文件头的 DOCTYPE 指向 mybatis.org 上的 DTD：不去下载它
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
        String namespace = root.getAttribute("namespace");
        String domain = domainOf(namespace);
        if (domain == null) {
            return List.of(source + "：namespace " + namespace + " 要是 com.lingmao.scm.module.<域> 下的 Mapper");
        }
        Map<String, Element> fragments = new HashMap<>();
        for (Element sql : children(root, Set.of("sql"))) {
            fragments.put(sql.getAttribute("id"), sql);
        }
        List<String> violations = new ArrayList<>();
        for (Element statement : children(root, Set.of("select", "insert", "update", "delete"))) {
            violations.addAll(checkSql(domain, source + " 的 " + statement.getAttribute("id"), textOf(statement, fragments)));
        }
        return violations;
    }

    private static List<String> checkSql(String domain, String where, String sql) {
        List<String> violations = new ArrayList<>();
        for (String table : writtenTables(sql)) {
            violations.addAll(checkWrite(domain, where, table));
        }
        return violations;
    }

    private static List<String> checkWrite(String domain, String where, String table) {
        List<String> prefixes = PREFIXES.get(domain);
        if (prefixes == null) {
            return List.of(where + "：域 " + domain + " 不在 TableOwnerTest.PREFIXES 里，先照架构 §3.1 写上它的表前缀");
        }
        if (!IDENTIFIER.matcher(table).matches()) {
            return List.of(where + "：写的表 " + table + " 不是固定的名字，扫不出是哪张表（${} 拼的表名、include 别的文件的片段）");
        }
        if (prefixes.stream().anyMatch(table::startsWith)) {
            return List.of();
        }
        return List.of(prefixes.isEmpty()
                ? where + "：" + domain + " 只读，不能写 " + table
                : where + "：" + domain + " 只能写 " + String.join("、", prefixes) + " 开头的表，不能写 " + table
                + "（它的主人是别的域，调那个域的 Service）");
    }

    /** SQL 里写到的表（小写、去掉 schema 和引号）。表名不是固定名字的原样返回，由 checkWrite 报出来 */
    static List<String> writtenTables(String rawSql) {
        String sql = normalize(rawSql);
        List<String> tables = new ArrayList<>();
        Matcher m = WRITE.matcher(sql);
        while (m.find()) {
            if (m.group(1).equalsIgnoreCase("UPDATE") && NOT_A_TABLE_UPDATE.contains(lastWordBefore(sql, m.start()))) {
                continue;
            }
            String table = m.group(2).replace("\"", "").toLowerCase(Locale.ROOT);
            Matcher qualified = SCHEMA_TABLE.matcher(table);
            tables.add(qualified.matches() ? qualified.group(1) : table);
        }
        return tables;
    }

    /** 去掉字符串和注释；注解里的 <script> 去掉标签（<set>、<if> 这些不影响表名） */
    private static String normalize(String sql) {
        String text = sql.contains("<script>") ? sql.replaceAll("<[^>]+>", " ") : sql;
        return LITERAL_OR_COMMENT.matcher(text).replaceAll(" ");
    }

    private static String lastWordBefore(String sql, int end) {
        Matcher m = LAST_WORD.matcher(sql.substring(0, end));
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    /** 方法上 MyBatis 注解里的 SQL；没有注解返回 null */
    private static String sqlOf(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String[] value = switch (annotation) {
                case Select select -> select.value();
                case Insert insert -> insert.value();
                case Update update -> update.value();
                case Delete delete -> delete.value();
                default -> null;
            };
            if (value != null) {
                return String.join(" ", value);
            }
        }
        return null;
    }

    /** 实体对应的表：@TableName 写了用它，没写按 MyBatis-Plus 的默认规则由类名转（StyleColor → style_color） */
    private static String tableOf(Class<?> entity) {
        TableName name = entity.getAnnotation(TableName.class);
        String table = name != null && !name.value().isEmpty() ? name.value() : StringUtils.camelToUnderline(entity.getSimpleName());
        return table.toLowerCase(Locale.ROOT);
    }

    /** com.lingmao.scm.module.<域>.xxx 里的 <域>；不在 module 下返回 null */
    private static String domainOf(String className) {
        if (className == null || !className.startsWith(MODULE)) {
            return null;
        }
        String rest = className.substring(MODULE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }

    private static List<Element> children(Element parent, Set<String> tags) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && tags.contains(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    /** 语句的全部文字：动态标签（<if>、<set>）里的照样取；include 展开成片段的文字，片段不在本文件里就留一个 ${refid} 让它被报出来 */
    private static String textOf(Node node, Map<String, Element> fragments) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = node.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node child = nodes.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getNodeValue());
            } else if (child instanceof Element element && element.getTagName().equals("include")) {
                Element fragment = fragments.get(element.getAttribute("refid"));
                text.append(fragment != null ? textOf(fragment, fragments) : "${" + element.getAttribute("refid") + "}");
            } else if (child instanceof Element element) {
                text.append(textOf(element, fragments));
            }
            text.append(' ');
        }
        return text.toString();
    }

    // ---------------------------------------------------------------- 故意违规的例子：只在这里，不在 src/main

    interface StyleWritesSupplier {
        @Update("UPDATE sup_supplier SET status = 'BLACKLISTED' WHERE id = #{id}")
        int blacklist(Long id);
    }

    interface StyleHidesWriteInSelect {
        @Select("WITH gone AS (DELETE FROM sup_supplier_cert WHERE supplier_id = #{id} RETURNING id) SELECT count(*) FROM gone")
        int clean(Long id);
    }

    interface StyleDynamicTable {
        @Delete("DELETE FROM ${table} WHERE id = #{id}")
        int delete(String table, Long id);
    }

    interface StyleProvider {
        @UpdateProvider(type = Object.class, method = "toString")
        int touch(Long id);
    }

    @TableName("sup_supplier")
    static class SupplierRow {
    }

    /** 没写 @TableName，表名按类名转成 style_color_row：前缀不是 sty_，也算违规 */
    static class StyleColorRow {
    }

    @TableName("sty_style")
    static class StyleRow {
    }

    interface StyleBaseMapperOfSupplier extends BaseMapper<SupplierRow> {
    }

    interface StyleBaseMapperNoTableName extends BaseMapper<StyleColorRow> {
    }

    interface StyleBaseMapperOfOwnTable extends BaseMapper<StyleRow> {
    }

    interface BoardWrites {
        @Insert("INSERT INTO prd_report (id) VALUES (#{id})")
        int report(Long id);
    }

    interface BoardBaseMapper extends BaseMapper<StyleRow> {
    }

    /** 都不该报的写法 */
    interface PurchaseAllowed {
        @Insert("INSERT INTO mrp_calc (id) VALUES (#{id}) ON CONFLICT (id) DO UPDATE SET update_time = now()")
        int upsert(Long id);

        @Update("UPDATE pur_order SET status = 'CLOSED', update_time = now() WHERE id = #{id}")
        int close(Long id);

        @Select("SELECT o.*, s.name FROM pur_order o JOIN sup_supplier s ON s.id = o.supplier_id WHERE o.id = #{id} FOR UPDATE OF o")
        Object lock(Long id);

        @Select("SELECT * FROM inv_stock WHERE id = #{id} FOR NO KEY UPDATE")
        Object lockStock(Long id);

        @Select("""
                -- 这里不会 UPDATE sup_supplier
                SELECT 'DELETE FROM sup_supplier' AS note, last_update, update_by FROM pur_order /* INSERT INTO sup_x */
                """)
        Object comments();

        @Update("<script>UPDATE pur_order_item <set><if test='qty != null'>qty = #{qty},</if></set> WHERE id = #{id}</script>")
        int partial(Long id, Long qty);
    }
}
