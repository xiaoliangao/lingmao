package com.lingmao.scm.module.techpack.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.price.dto.ProcessPriceKey;
import com.lingmao.scm.module.price.service.PriceService;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.dto.StyleCostRowVO;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.techpack.service.TechPackCostService;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** #13：真实不同资料包/物料/工厂的批量核价，不以事务一级缓存或并行调用掩盖 N+1。 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A1CostBatchIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionFactory sessions;
    @Autowired JsonMapper json;
    @Autowired Clock clock;
    @Autowired TechPackCostService costs;
    @Autowired TechPackMapper packs;
    @Autowired PackOwners owners;
    @Autowired PriceService prices;
    private final List<Long> packIds = new ArrayList<>();
    private final List<Long> styleIds = new ArrayList<>();
    private final List<Long> materialIds = new ArrayList<>();
    private final List<Long> factories = new ArrayList<>();
    private final List<Long> groups = new ArrayList<>();
    private final List<Long> colors = new ArrayList<>();
    private String prefix;

    @BeforeAll
    void seedDifferentCostInputs() {
        prefix = "CB" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long user = id("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','A1 cost') RETURNING id", prefix);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user, "A1 cost"), null,
                List.of(new SimpleGrantedAuthority("data:all"), new SimpleGrantedAuthority("techpack:cost:query"))));
        long category = id("INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id", prefix);
        long materialCategory = id("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", prefix);
        LocalDate today = LocalDate.now(clock);
        for (int n = 1; n <= 50; n++) {
            String code = prefix + String.format("%03d", n);
            long factory = id("INSERT INTO sup_supplier(code,name,short_name,status) VALUES (?,? ,?,'ACTIVE') RETURNING id", code, code, code);
            factories.add(factory);
            long group = id("INSERT INTO bas_size_group(name,base_size) VALUES (?,'M') RETURNING id", code);
            groups.add(group);
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,size_code,seq) VALUES (?,'M',1),(?,'L',2)", group, group);
            long color = TestColors.insert(jdbc, code); colors.add(color);
            long style = id("""
                    INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id,status,default_factory_id,tag_price)
                    VALUES (?,?,'FS','A1_COST',?,2026,'SPRING','DESIGN',?,'ENABLED',?,200) RETURNING id
                    """, code, code, category, group, factory);
            styleIds.add(style);
            jdbc.update("INSERT INTO sty_style_color(style_id,color_id,seq,status) VALUES (?,?,1,1)", style, color);
            jdbc.update("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES (?,?,?,'M')", style, code, color);
            long fabric = material(code + "F", materialCategory, "FABRIC");
            long trim = material(code + "T", materialCategory, "TRIM");
            long fabricSku = id("INSERT INTO bas_material_sku(material_id,code,color_id) VALUES (?,?,?) RETURNING id", fabric, code + "FS", color);
            long trimSku = id("INSERT INTO bas_material_sku(material_id,code) VALUES (?,?) RETURNING id", trim, code + "TS");
            // 外发的：每三个留一个没填加工费，取价格档案的清加工价（RFC 011 §3.1）
            long pack = id("INSERT INTO tp_tech_pack(style_id,stage,version,status,cost_mode,buy_price,labor_price) VALUES (?,'PROD',1,'EFFECTIVE',?,?,?) RETURNING id",
                    style, n % 2 == 0 ? "BUY" : "OUTSOURCE", n % 4 == 0 ? new BigDecimal("7.777") : null,
                    n % 2 == 1 && n % 3 != 0 ? new BigDecimal("3.5801") : null);
            packIds.add(pack);
            long bom = id("""
                    INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_mode,unit,loss_pct,ref_price)
                    VALUES (?,1,'MAIN',?,'BY_COLOR_SIZE','BY_SIZE','METER',3,2) RETURNING id
                    """, pack, fabric);
            jdbc.update("INSERT INTO tp_bom_item_sku(bom_item_id,color_id,size_code,material_sku_id,ref_price) VALUES (?,?,'M',?,2.675)", bom, color, fabricSku);
            jdbc.update("INSERT INTO tp_bom_item_size(bom_item_id,size_code,usage_qty) VALUES (?,'M',1.5),(?,'L',9)", bom, bom);
            long trimBom = id("INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,usage_qty,unit,ref_price) VALUES (?,2,'TRIM',?,3,'METER',0.125) RETURNING id", pack, trim);
            jdbc.update("INSERT INTO tp_bom_item_sku(bom_item_id,material_sku_id) VALUES (?,?)", trimBom, trimSku);
            jdbc.update("INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,usage_qty,unit,ref_price,source) VALUES (?,3,'FACTORY',?,1,'METER',999,'FACTORY')", pack, fabric);
            jdbc.update("INSERT INTO tp_cost_item(tech_pack_id,seq,name,amount) VALUES (?,1,'A',0.005),(?,2,'B',0.005)", pack, pack);
            if (n != 50) {
                price(factory, style, "FULL", "1", today.minusDays(2), false);
                price(factory, style, "FULL", Integer.toString(10 + n), today.minusDays(1), false);
                price(factory, style, "FULL", Integer.toString(20 + n), today.minusDays(1), false); // 同日最后录入。
            }
            price(factory, style, "FULL", "1000", today.plusDays(1), false);
            price(factory, style, "FULL", "2000", today, true);
            price(factory, style, "CMT", "7777", today, false);
            if (n % 2 == 0) {
                var snapshot = PackOwners.snapshot(owners.context(packs.selectById(pack)));
                jdbc.update("UPDATE tp_tech_pack SET owner_snapshot=?::jsonb WHERE id=?", json.writeValueAsString(snapshot), pack);
                // 生效快照不可被当前款式改动替换：换工厂和基码后仍取冻结版本的核价上下文。
                jdbc.update("UPDATE sty_style SET default_factory_id=? WHERE id=?", factories.getFirst(), style);
                jdbc.update("UPDATE bas_size_group SET base_size='L' WHERE id=?", group);
            }
        }
    }

    @Test
    void oneAndFiftyDifferentPacksUseConstantSelectsAndExactlyTheSameCosts() throws Exception {
        StyleCostQuery one = new StyleCostQuery(); one.setKeyword(prefix + "001"); one.setSize(100);
        StyleCostQuery fifty = new StyleCostQuery(); fifty.setKeyword(prefix); fifty.setSize(100);
        Captured small = capture(() -> costs.styleCosts(one));
        Captured large = capture(() -> costs.styleCosts(fifty));
        assertThat(small.rows().getTotal()).isEqualTo(1);
        assertThat(large.rows().getTotal()).isEqualTo(50);
        assertThat(large.sql()).hasSize(small.sql().size());
        assertThat(large.sql().stream().filter(sql -> sql.contains("prc_price")).toList()).hasSize(1);
        assertThat(large.sql().stream().filter(sql -> sql.contains("from tp_bom_item ")).toList()).hasSize(1);
        Map<Long, StyleCostRowVO> byPack = large.rows().getList().stream().collect(Collectors.toMap(StyleCostRowVO::getTechPackId, r -> r));
        for (int index = 0; index < 50; index++) {
            int n = index + 1;
            long pack = packIds.get(index);
            var row = byPack.get(pack);
            var single = costs.cost(pack, null);
            assertThat(row.getFabric()).isEqualByComparingTo("4.13").isEqualByComparingTo(single.getSummary().fabric());
            assertThat(row.getTrim()).isEqualByComparingTo("0.38").isEqualByComparingTo(single.getSummary().trim());
            assertThat(row.getOther()).isEqualByComparingTo("0.01").isEqualByComparingTo(single.getSummary().other());
            String labor = n % 2 == 1 ? (n % 3 == 0 ? "7777" : "3.58") : n % 4 == 0 ? "7.78" : n == 50 ? "0.00" : Integer.toString(20 + n);
            assertThat(row.getLabor()).isEqualByComparingTo(labor).isEqualByComparingTo(single.getSummary().labor());
            assertThat(row.getTotal()).isEqualByComparingTo(single.getSummary().total());
            assertThat(row.getRatio()).isEqualByComparingTo(single.getRatio());
            assertThat(single.getBaseSize()).isEqualTo("M");
            assertThat(single.getDefaultFactoryId()).isEqualTo(factories.get(index));
        }
        List<ProcessPriceKey> keys = new ArrayList<>();
        for (int n = 0; n < 50; n++) keys.add(new ProcessPriceKey(factories.get(n), styleIds.get(n), "FULL"));
        var batch = prices.currentProcesses(keys, LocalDate.now(clock));
        assertThat(batch).hasSize(49);
        for (var key : keys) assertThat(batch.get(key)).isEqualTo(prices.currentProcess(key.supplierId(), key.styleId(), key.processMode(), LocalDate.now(clock)).orElse(null));
        Path artifacts = Path.of("target", "a1-production"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("style-cost-query-count.json"), json.writeValueAsString(Map.of(
                "oneSelects", small.sql().size(), "fiftySelects", large.sql().size(), "one", small.sql(), "fifty", large.sql(),
                "exactCostsCompared", 50, "distinctFactories", 50, "distinctMaterials", 100)));
    }

    @Test
    void batchCurrentProcessReadsOneRowPerKeyWith10000SameDayPrices() throws Exception {
        LocalDate date = LocalDate.now(clock).minusDays(1);
        for (int start = 1; start <= 10000; start += 1000) {
            jdbc.update("""
                    INSERT INTO prc_price(type,supplier_id,style_id,process_mode,price,unit,tax_pct,effective_date,reason)
                    SELECT 'PROCESS',?,?,'FULL',CASE WHEN n=10000 THEN 123.4567 ELSE 21 END,'PIECE',13,?,'A1 history'
                    FROM generate_series(?,?) n
                    """, factories.getFirst(), styleIds.getFirst(), date, start, start + 999);
        }
        jdbc.execute("ANALYZE prc_price");
        long latest = jdbc.queryForObject("SELECT max(id) FROM prc_price WHERE supplier_id=? AND style_id=? AND reason='A1 history'", Long.class,
                factories.getFirst(), styleIds.getFirst());
        List<ProcessPriceKey> keys = new ArrayList<>();
        for (int n = 0; n < 50; n++) keys.add(new ProcessPriceKey(factories.get(n), styleIds.get(n), "FULL"));
        var capture = SqlStatementCapture.capture(() -> prices.currentProcesses(keys, LocalDate.now(clock)));
        var batch = capture.result();
        assertThat(batch).hasSize(49);
        assertThat(batch.get(keys.getFirst()).id()).isEqualTo(latest);
        assertThat(batch.get(keys.getFirst()).price()).isEqualByComparingTo("123.4567");
        List<BoundSql> captured = capture.statements();
        assertThat(captured).hasSize(1);
        BoundSql sql = captured.getFirst();
        String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                var mapped = sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.price.mapper.PriceMapper.selectCurrentProcesses");
                new DefaultParameterHandler(mapped, sql.getParameterObject(), sql).setParameters(statement);
                try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
            }
        });
        List<JsonNode> nodes = nodes(json.readTree(plan).get(0).get("Plan"));
        assertThat(nodes.stream().filter(n -> n.has("Index Name")).map(n -> n.get("Index Name").asString()).toList())
                .contains("idx_prc_process_current");
        List<JsonNode> priceReads = nodes.stream().filter(n -> "prc_price".equals(n.path("Relation Name").asString())).toList();
        assertThat(priceReads).isNotEmpty().allSatisfy(n -> {
            double visited = (n.path("Actual Rows").asDouble() + n.path("Rows Removed by Filter").asDouble()
                    + n.path("Rows Removed by Index Recheck").asDouble()) * n.path("Actual Loops").asDouble();
            assertThat(visited).isLessThanOrEqualTo(keys.size());
        });
        Path artifacts = Path.of("target", "a1-production"); Files.createDirectories(artifacts);
        Files.writeString(artifacts.resolve("price-current-history-plan.json"), plan);
        Files.writeString(artifacts.resolve("price-current-history-sql.txt"), sql.getSql());
    }

    private static List<JsonNode> nodes(JsonNode root) {
        List<JsonNode> result = new ArrayList<>(); result.add(root);
        if (root.has("Plans")) root.get("Plans").forEach(child -> result.addAll(nodes(child)));
        return result;
    }

    @AfterAll
    void cleanup() {
        if (!packIds.isEmpty()) {
            String ids = ids(packIds);
            jdbc.update("DELETE FROM tp_bom_item_sku WHERE bom_item_id IN (SELECT id FROM tp_bom_item WHERE tech_pack_id IN (" + ids + "))");
            jdbc.update("DELETE FROM tp_bom_item_size WHERE bom_item_id IN (SELECT id FROM tp_bom_item WHERE tech_pack_id IN (" + ids + "))");
            for (String table : List.of("tp_bom_item", "tp_cost_item")) jdbc.update("DELETE FROM " + table + " WHERE tech_pack_id IN (" + ids + ")");
            delete("tp_tech_pack", "id", packIds);
        }
        delete("prc_price", "style_id", styleIds);
        delete("sty_style_sku", "style_id", styleIds); delete("sty_style_color", "style_id", styleIds); delete("sty_style", "id", styleIds);
        delete("bas_material_sku", "material_id", materialIds); delete("bas_material", "id", materialIds);
        delete("sup_supplier", "id", factories); delete("bas_size_group_item", "size_group_id", groups); delete("bas_size_group", "id", groups);
        delete("bas_color", "id", colors);
        SecurityContextHolder.clearContext();
    }

    private long material(String code, long category, String type) {
        long id = id("INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit) VALUES (?,?,?,?,'METER','METER') RETURNING id", code, code, category, type);
        materialIds.add(id); return id;
    }
    private void price(long factory, long style, String mode, String value, LocalDate date, boolean voided) {
        jdbc.update("INSERT INTO prc_price(type,supplier_id,style_id,process_mode,price,unit,tax_pct,effective_date,voided,void_reason) VALUES ('PROCESS',?,?,?,?, 'PIECE',13,?,?,?)",
                factory, style, mode, new BigDecimal(value), date, voided, voided ? "A1 void" : null);
    }
    private long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static String ids(List<Long> ids) { return ids.stream().map(String::valueOf).collect(Collectors.joining(",")); }
    private void delete(String table, String column, List<Long> ids) { if (!ids.isEmpty()) jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + ids(ids) + ")"); }
    private Captured capture(Supplier<PageResult<StyleCostRowVO>> query) {
        var captured = SqlStatementCapture.capture(query);
        return new Captured(captured.result(), sqlText(captured.statements()));
    }
    private static List<String> sqlText(List<BoundSql> statements) {
        return statements.stream().map(sql -> sql.getSql().toLowerCase().replaceAll("\\s+", " ")).toList();
    }
    private record Captured(PageResult<StyleCostRowVO> rows, List<String> sql) { }
}
