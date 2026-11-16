package com.lingmao.scm;

import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.MenuVO;
import com.lingmao.scm.module.system.entity.RoleDataScope;
import com.lingmao.scm.module.system.service.PermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 迁移完的库结构守不守约定（数据库设计 §1、架构 §6）。查的是 pg_catalog，以后每批的新表自动受检查，不用改这个测试：
 * <ul>
 *   <li>每张表有表注释：外键拦住删除时，1005"已被××引用"拿它当中文名；</li>
 *   <li>外键不级联：删除、改主键都是 RESTRICT 或 NO ACTION，"用过的只能停用"靠它；</li>
 *   <li>外键列是某个索引的第一列：删被引用的一方时数据库要按它查，没有索引就是全表扫；</li>
 *   <li>叫 xxx_id 的列都有外键（"一个都不能省"），例外在 {@link #NO_FK} 里一条一条写理由；</li>
 *   <li>逻辑删除只有 sys_ 表；数量、金额不用浮点。</li>
 * </ul>
 * 后半是第 2 批迁移（V10–V16）的预置数据：内置字典项、八个角色模板的数据范围和授权；删被引用的主数据报 23503。
 * <p>
 * 别的 IT 临时建、测完删的表以 it_ 开头（GlobalExceptionHandlerIT、StatusCasIT），不算库结构。
 * 这里建的数据用随机编码，测完按 id 删掉
 */
@IntegrationTest
class SchemaConventionsIT {

    /** 叫 xxx_id、却没有外键的列，每一条都要有理由。以后加的"类型 + id"列、物权 owner_id（自有存 0）照这样加进来 */
    private static final Map<String, String> NO_FK = Map.ofEntries(
            Map.entry("sys_menu.parent_id", "V4 的写法：顶层是 0，0 加不了外键（数据库设计 §3.4）"),
            Map.entry("sys_user_role.user_id", "V5 的关联表没加外键，补外键要先清孤儿数据，不补（数据库设计 §3.6）"),
            Map.entry("sys_user_role.role_id", "同上"),
            Map.entry("sys_role_menu.role_id", "同上"),
            Map.entry("sys_role_menu.menu_id", "同上"),
            Map.entry("sys_oper_log.user_id", "操作日志只插不删，人删了日志照样留着"),
            Map.entry("biz_doc_log.doc_id", "类型 + id 指向各种单据，加不了外键（数据库设计 §1.4）"),
            Map.entry("biz_doc_log.operator_id", "审计人，和 create_by 一样不加外键"),
            Map.entry("biz_attachment.doc_id", "类型 + id"),
            Map.entry("prc_price.source_id", "类型 + id：来源单据"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GlobalExceptionHandler handler;

    @Autowired
    private PermissionService permissionService;

    /** 测试里插的行，测完倒着删：后插的引用先插的 */
    private final Deque<String> cleanUps = new ArrayDeque<>();

    @AfterEach
    void cleanUp() {
        while (!cleanUps.isEmpty()) {
            jdbc.update(cleanUps.pop());
        }
    }

    // ---------------- 约定 ----------------

    /** 规则：每张表都写了 COMMENT ON TABLE（已有的 sys_、biz_ 表也是） */
    @Test
    void everyTableHasComment() {
        assertThat(tables()).as("没查到表，查错库或 schema 了").hasSizeGreaterThan(25);

        List<String> missing = jdbc.queryForList("""
                SELECT c.relname FROM pg_class c
                WHERE c.relnamespace = current_schema()::regnamespace AND c.relkind IN ('r', 'p')
                  AND c.relname <> 'flyway_schema_history' AND c.relname NOT LIKE 'it\\_%'
                  AND coalesce(btrim(obj_description(c.oid, 'pg_class')), '') = ''
                ORDER BY 1
                """, String.class);

        assertThat(missing).as("没写表注释的表：1005 的提示会变成\"已被其他数据引用\"").isEmpty();
    }

    /** 规则：外键都不级联。CASCADE 会把引用它的数据一起删掉，SET NULL 会悄悄断开引用，"用过的只能停用"就不成立了 */
    @Test
    void foreignKeysNeverCascade() {
        assertThat(foreignKeyCount()).as("没查到外键").hasSizeGreaterThanOrEqualTo(30);

        List<String> cascading = jdbc.queryForList("""
                SELECT conrelid::regclass::text || ' ' || conname::text || '：删除 ' || confdeltype::text || '，改主键 ' || confupdtype::text
                FROM pg_constraint
                WHERE contype = 'f' AND connamespace = current_schema()::regnamespace
                  AND (confdeltype NOT IN ('a', 'r') OR confupdtype NOT IN ('a', 'r'))
                ORDER BY 1
                """, String.class);

        assertThat(cascading).as("动作不是 NO ACTION（a）、RESTRICT（r）的外键").isEmpty();
    }

    /**
     * 规则：每个外键的列是某个索引的前几列。部分索引只认 WHERE 列 IS NOT NULL 的
     * （uk_bas_warehouse_factory 这种：按 列 = ? 查时用得上），别的条件（WHERE status = 'PENDING'）删除时用不上
     */
    @Test
    void foreignKeyColumnsAreIndexed() {
        List<String> unindexed = jdbc.queryForList("""
                SELECT c.conrelid::regclass::text || '.' || a.attname
                FROM pg_constraint c
                JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]
                WHERE c.contype = 'f' AND c.connamespace = current_schema()::regnamespace
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_index i
                      WHERE i.indrelid = c.conrelid
                        AND cardinality(c.conkey) <= i.indnkeyatts
                        AND (SELECT array_agg(i.indkey[k] ORDER BY k)
                             FROM generate_series(0, cardinality(c.conkey) - 1) AS k) = c.conkey
                        AND (i.indpred IS NULL
                             OR pg_get_expr(i.indpred, i.indrelid) = '(' || quote_ident(a.attname) || ' IS NOT NULL)'))
                ORDER BY 1
                """, String.class);

        assertThat(unindexed).as("外键列不是任何索引的第一列").isEmpty();
    }

    /** 规则：外键一个都不能省。叫 xxx_id 的 BIGINT 列都要有外键，没有的必须写进 NO_FK 并说明理由 */
    @Test
    void idColumnsHaveForeignKeys() {
        List<String> withoutFk = jdbc.queryForList("""
                SELECT c.table_name::text || '.' || c.column_name::text
                FROM information_schema.columns c
                WHERE c.table_schema = current_schema() AND c.data_type = 'bigint' AND c.column_name LIKE '%\\_id'
                  AND c.table_name NOT LIKE 'it\\_%' AND c.table_name <> 'flyway_schema_history'
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_constraint k
                      JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = ANY (k.conkey)
                      WHERE k.contype = 'f' AND k.conrelid = format('%I', c.table_name::text)::regclass
                        AND a.attname::text = c.column_name::text)
                ORDER BY 1
                """, String.class);

        assertThat(withoutFk).as("没有外键的 xxx_id 列（真不要外键的，写进 NO_FK 说明理由）")
                .containsExactlyInAnyOrderElementsOf(NO_FK.keySet());
    }

    /** 规则：新业务表不用逻辑删除（架构 §6），只有 V1–V7 的 sys_ 表有 deleted；数量、单价、金额不用浮点（PRD §7.3） */
    @Test
    void noLogicalDeleteOutsideSys_noFloat() {
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND column_name = 'deleted' AND table_name NOT LIKE 'sys\\_%'
                """, String.class)).as("sys_ 以外有 deleted 列的表").isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = current_schema() AND data_type IN ('real', 'double precision', 'money')
                """, String.class)).as("浮点列").isEmpty();
    }

    // ---------------- 第 2 批的预置数据 ----------------

    /**
     * 规则：数据库设计 §3.2 的字典类型都在；内置项正好是 PRD §6.1.7 规则 2 的那 11 个；
     * 品牌预置为空（甲方的名字不进公开仓库）；物料类型带上了 Q40 的配件、工艺、鞋底，备料比默认 0；生产节点带默认天数
     */
    @Test
    void dictPresets() {
        assertThat(jdbc.queryForList("SELECT code FROM sys_dict_type WHERE deleted = false", String.class))
                .contains("style_class", "season", "wave", "style_tag", "brand", "series", "gender", "style_source",
                        "exec_standard", "safety_category", "color_family", "unit", "currency", "tax_rate",
                        "settle_method", "invoice_type", "material_type", "supplier_category", "order_type",
                        "process_mode", "prod_node", "issue_type", "bom_part", "process_category", "size_point",
                        "packing_mode", "cert_type", "stock_in_reason", "stock_out_reason");

        assertThat(jdbc.queryForList(
                "SELECT type_code || ':' || value FROM sys_dict_data WHERE builtin AND deleted = false", String.class))
                .containsExactlyInAnyOrder("style_source:CUSTOMER_SAMPLE", "material_type:FABRIC",
                        "material_type:LINING", "supplier_category:GARMENT", "supplier_category:PROCESSOR",
                        "order_type:CUSTOMER", "process_mode:FULL", "prod_node:CUT", "cert_type:LICENSE",
                        "stock_in_reason:OPENING", "currency:CNY", "unit:PIECE", "style_source:OWN");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_dict_data WHERE type_code = 'brand'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("""
                SELECT label || '=' || num_value::int FROM sys_dict_data
                WHERE type_code = 'material_type' AND deleted = false ORDER BY sort
                """, String.class))
                .containsExactly("面料=0", "里料=0", "辅料=0", "包材=0", "标签吊牌=0", "配件=0", "工艺=0", "鞋底=0");
        assertThat(jdbc.queryForMap("""
                SELECT t.num_label, d.num_value::int AS days FROM sys_dict_type t
                JOIN sys_dict_data d ON d.type_code = t.code AND d.deleted = false
                WHERE t.code = 'prod_node' AND t.deleted = false AND d.value = 'CUT'
                """)).containsEntry("num_label", "默认天数").containsEntry("days", 4);
    }

    /** RFC 003 §13.2 的表：看 = list、export；改 = 看 + add、edit、remove、import */
    private static final Set<String> BASICS_VIEW = Set.of("basic:basics:list");
    private static final Set<String> BASICS_EDIT = Set.of("basic:basics:list", "basic:basics:add",
            "basic:basics:edit", "basic:basics:remove");
    private static final Set<String> MATERIAL_VIEW = Set.of("basic:material:list", "basic:material:export");
    private static final Set<String> MATERIAL_EDIT = union(MATERIAL_VIEW, Set.of("basic:material:add",
            "basic:material:edit", "basic:material:remove", "basic:material:import"));
    private static final Set<String> SUPPLIER_VIEW = Set.of("supplier:supplier:list", "supplier:supplier:export");
    private static final Set<String> SUPPLIER_EDIT = union(SUPPLIER_VIEW, Set.of("supplier:supplier:add",
            "supplier:supplier:edit", "supplier:supplier:remove", "supplier:supplier:import"));
    private static final Set<String> STYLE_VIEW = Set.of("style:style:list", "style:style:export");
    private static final Set<String> STYLE_EDIT = union(STYLE_VIEW, Set.of("style:style:add", "style:style:edit",
            "style:style:remove", "style:style:import"));
    private static final Set<String> PRICE_VIEW = Set.of("price:price:list");
    private static final Set<String> BANK = Set.of("supplier:supplier:bank");

    /** 第 3、4 批（V17，docs/api 各模块的菜单一节）照 PRD §3.1：看 = list、export；改 = 看 + 新建、编辑、删除、提交、作废、导入 */
    private static final Set<String> PACK_VIEW = Set.of("techpack:pack:list");
    private static final Set<String> PACK_EDIT = union(PACK_VIEW, Set.of("techpack:pack:add", "techpack:pack:edit",
            "techpack:pack:remove", "techpack:pack:publish"));
    private static final Set<String> COST = Set.of("techpack:cost:query");
    private static final Set<String> STANDARD_VIEW = Set.of("techpack:standard:list");
    private static final Set<String> STANDARD_EDIT = union(STANDARD_VIEW, Set.of("techpack:standard:edit"));
    private static final Set<String> ORDER_VIEW = Set.of("order:order:list", "order:order:export");
    private static final Set<String> ORDER_EDIT = union(ORDER_VIEW, Set.of("order:order:add", "order:order:edit",
            "order:order:remove", "order:order:submit", "order:order:void", "order:order:import"));
    private static final Set<String> CHANGE_VIEW = Set.of("order:change:list");
    private static final Set<String> CHANGE_EDIT = union(CHANGE_VIEW, Set.of("order:change:add", "order:change:edit",
            "order:change:remove", "order:change:submit", "order:change:void"));
    private static final Set<String> PURCHASE_VIEW = Set.of("purchase:mrp:list", "purchase:order:list", "purchase:order:export");
    private static final Set<String> PURCHASE_EDIT = union(PURCHASE_VIEW, Set.of("purchase:mrp:add", "purchase:mrp:edit",
            "purchase:mrp:remove", "purchase:order:add", "purchase:order:edit", "purchase:order:remove",
            "purchase:order:submit", "purchase:order:void"));
    /** 用户 11-15 定：采购单、生产单的结案单独一个码，给经办的（采购、生产跟单）和管理层 */
    private static final Set<String> PURCHASE_CLOSE = Set.of("purchase:order:close");
    private static final Set<String> STOCK_VIEW = Set.of("inventory:doc:list", "inventory:doc:export",
            "inventory:stock:list", "inventory:stock:export");
    private static final Set<String> STOCK_EDIT = union(STOCK_VIEW, Set.of("inventory:doc:add", "inventory:doc:edit",
            "inventory:doc:remove", "inventory:doc:audit"));
    private static final Set<String> COUNT_VIEW = Set.of("inventory:count:list", "inventory:count:export");
    private static final Set<String> COUNT_EDIT = union(COUNT_VIEW, Set.of("inventory:count:add", "inventory:count:edit",
            "inventory:count:remove", "inventory:count:submit", "inventory:count:import"));
    private static final Set<String> PRODUCTION_VIEW = Set.of("production:order:list", "production:order:export",
            "production:board:list");
    private static final Set<String> PRODUCTION_EDIT = union(PRODUCTION_VIEW, Set.of("production:order:add",
            "production:order:edit", "production:order:remove", "production:order:submit", "production:order:void",
            "production:order:close", "production:node:edit", "production:issue:edit", "production:receipt:add",
            "production:receipt:void", "production:mobile:list"));

    private static final Map<String, Set<String>> ROLE_PERMS = new LinkedHashMap<>();

    static {
        // 每行前半是第 2 批（V16），后半是第 3、4 批（V17）
        ROLE_PERMS.put("dev", union(BASICS_EDIT, MATERIAL_EDIT, SUPPLIER_VIEW, STYLE_EDIT, PRICE_VIEW,
                PACK_EDIT, STANDARD_VIEW, ORDER_VIEW, CHANGE_VIEW, PRODUCTION_VIEW));
        ROLE_PERMS.put("dev_lead", union(BASICS_EDIT, MATERIAL_EDIT, SUPPLIER_VIEW, STYLE_EDIT, PRICE_VIEW,
                PACK_EDIT, COST, STANDARD_EDIT, ORDER_VIEW, CHANGE_VIEW, PRODUCTION_VIEW));
        ROLE_PERMS.put("sales", union(BASICS_VIEW, MATERIAL_VIEW, SUPPLIER_VIEW, STYLE_VIEW, PRICE_VIEW,
                PACK_VIEW, ORDER_EDIT, CHANGE_EDIT, PURCHASE_VIEW, PRODUCTION_VIEW));
        ROLE_PERMS.put("merchandiser", union(BASICS_VIEW, MATERIAL_VIEW, SUPPLIER_VIEW, STYLE_VIEW, PRICE_VIEW,
                Set.of("price:process:edit"),
                PACK_VIEW, STANDARD_VIEW, ORDER_VIEW, CHANGE_EDIT, PURCHASE_VIEW, STOCK_VIEW, PRODUCTION_EDIT));
        ROLE_PERMS.put("buyer", union(BASICS_VIEW, MATERIAL_EDIT, SUPPLIER_EDIT, BANK, STYLE_VIEW, PRICE_VIEW,
                Set.of("price:material:edit"),
                PACK_VIEW, COST, ORDER_VIEW, CHANGE_VIEW, PURCHASE_EDIT, PURCHASE_CLOSE, STOCK_VIEW, COUNT_VIEW,
                PRODUCTION_VIEW));
        ROLE_PERMS.put("warehouse", union(BASICS_VIEW, MATERIAL_VIEW, SUPPLIER_VIEW, STYLE_VIEW,
                PACK_VIEW, PURCHASE_VIEW, STOCK_EDIT, COUNT_EDIT, PRODUCTION_VIEW));
        ROLE_PERMS.put("finance", union(BASICS_VIEW, MATERIAL_VIEW, SUPPLIER_VIEW, BANK, STYLE_VIEW, PRICE_VIEW,
                PACK_VIEW, COST, ORDER_VIEW, CHANGE_VIEW, PURCHASE_VIEW, STOCK_VIEW, COUNT_VIEW, PRODUCTION_VIEW));
        ROLE_PERMS.put("manager", union(BASICS_VIEW, MATERIAL_VIEW, SUPPLIER_VIEW, Set.of("supplier:supplier:admit"),
                BANK, STYLE_VIEW, PRICE_VIEW, Set.of("price:material:edit", "price:process:edit"),
                PACK_VIEW, COST, STANDARD_VIEW, ORDER_VIEW, Set.of("order:order:audit"), CHANGE_VIEW,
                Set.of("order:change:audit"), PURCHASE_VIEW, Set.of("purchase:order:audit"), PURCHASE_CLOSE, STOCK_VIEW,
                COUNT_VIEW, Set.of("inventory:count:audit"), PRODUCTION_VIEW,
                Set.of("production:order:audit", "production:order:close")));
    }

    /** 各角色的侧边栏：目录 → 下面看得到的页面（按 sort）。"价格档案""资料包""工艺标准"（V18 起是资料包里的抽屉）是 visible = false 的，不出现 */
    private static final List<String> MASTER = List.of("供应商", "物料", "基础");
    private static final List<String> ORDERS = List.of("需求订单", "变更");
    private static final List<String> SUPPLY_ALL = List.of("算料", "采购", "物料入库单", "物料出库单", "物料盘点单", "库存");
    private static final List<String> MAKE = List.of("外发加工单", "成品采购单", "看板");
    private static final Map<String, Map<String, List<String>>> ROLE_SIDEBARS = Map.of(
            "dev", Map.of("开发", List.of("款式"), "订单", ORDERS, "生产", MAKE, "资料", MASTER),
            "dev_lead", Map.of("开发", List.of("款式", "核价"), "订单", ORDERS, "生产", MAKE, "资料", MASTER),
            "sales", Map.of("开发", List.of("款式"), "订单", ORDERS, "物料", List.of("算料", "采购"), "生产", MAKE, "资料", MASTER),
            "merchandiser", Map.of("开发", List.of("款式"), "订单", ORDERS,
                    "物料", List.of("算料", "采购", "物料入库单", "物料出库单", "库存"),
                    "生产", List.of("外发加工单", "成品采购单", "看板", "手机报工"), "资料", MASTER),
            "buyer", Map.of("开发", List.of("款式", "核价"), "订单", ORDERS, "物料", SUPPLY_ALL, "生产", MAKE, "资料", MASTER),
            "warehouse", Map.of("开发", List.of("款式"), "物料", SUPPLY_ALL, "生产", MAKE, "资料", MASTER),
            "finance", Map.of("开发", List.of("款式", "核价"), "订单", ORDERS, "物料", SUPPLY_ALL, "生产", MAKE, "资料", MASTER),
            "manager", Map.of("开发", List.of("款式", "核价"), "订单", ORDERS, "物料", SUPPLY_ALL, "生产", MAKE,
                    "资料", MASTER));

    /** 数据范围照 PRD §3.2；开发、开发主管给"全部"（RFC 003 §13.2、§17 第 3 条） */
    private static final Map<String, RoleDataScope> ROLE_SCOPES = Map.of(
            "dev", RoleDataScope.ALL, "dev_lead", RoleDataScope.ALL, "sales", RoleDataScope.SELF,
            "merchandiser", RoleDataScope.SELF, "buyer", RoleDataScope.SELF, "warehouse", RoleDataScope.ALL,
            "finance", RoleDataScope.ALL, "manager", RoleDataScope.ALL);

    /**
     * 规则：八个角色模板都在，数据范围对；只给一个人这一个角色，他拿到的业务权限码正好是 PRD §3.1 那一行
     * （第 2 批 RFC 003 §13.2，第 3、4 批 V17），data:all 跟着数据范围走；侧边栏正好是 ROLE_SIDEBARS 那样（目录也授了才挂得上），
     * 没有"价格档案""资料包""工艺标准"（visible = false，只为在角色页里勾），也没有管理后台的"审批规则"。走的是登录时真正用的 PermissionService
     */
    @Test
    void roleTemplates() {
        Map<String, RoleDataScope> scopes = jdbc.queryForList(
                        "SELECT code, data_scope FROM sys_role WHERE deleted = false AND code IN (" + inList(ROLE_PERMS.keySet()) + ")")
                .stream().collect(Collectors.toMap(row -> (String) row.get("code"),
                        row -> RoleDataScope.valueOf((String) row.get("data_scope"))));
        assertThat(scopes).isEqualTo(ROLE_SCOPES);

        long userId = insertId("INSERT INTO sys_user (username, password, nickname) VALUES (?, '不用登录', '测试')",
                "it_" + UUID.randomUUID().toString().substring(0, 8));
        cleanUps.push("DELETE FROM sys_user WHERE id = " + userId);
        cleanUps.push("DELETE FROM sys_user_role WHERE user_id = " + userId);

        for (Map.Entry<String, Set<String>> role : ROLE_PERMS.entrySet()) {
            jdbc.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id) SELECT ?, id FROM sys_role WHERE code = ? AND deleted = false",
                    userId, role.getKey());

            Set<String> perms = permissionService.loadPermissions(userId);
            assertThat(perms.stream().filter(p -> p.matches("(basic|supplier|style|price|techpack|order|purchase|inventory|production|biz):.*"))
                    .collect(Collectors.toSet()))
                    .as(role.getKey() + " 的权限码").isEqualTo(role.getValue());
            assertThat(perms.contains(RoleDataScope.ALL_AUTHORITY))
                    .as(role.getKey() + " 有没有 data:all").isEqualTo(ROLE_SCOPES.get(role.getKey()) == RoleDataScope.ALL);

            Map<String, List<String>> sidebar = permissionService.loadMenuTree(userId).stream()
                    .collect(Collectors.toMap(MenuVO::name, dir -> dir.children().stream().map(MenuVO::name).toList()));
            assertThat(sidebar).as(role.getKey() + " 的侧边栏").isEqualTo(ROLE_SIDEBARS.get(role.getKey()));
        }
    }

    /** 规则：删一个被引用的主数据，外键拦住（23503），翻成 1005，提示里是引用方的表注释 */
    @Test
    void deleteReferencedColor_blockedByForeignKey() {
        long colorId = color();
        long skuId = materialSku(colorId);

        DataIntegrityViolationException e = catchThrowableOfType(DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM bas_color WHERE id = ?", colorId));

        assertThat(sqlState(e)).isEqualTo("23503");
        R<Void> r = handler.handleDataIntegrity(e, new MockHttpServletRequest("DELETE", "/api/colors/" + colorId));
        assertThat(r.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM);
        assertThat(r.getMsg()).isEqualTo("已被物料 SKU引用，不能删除，只能停用");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material_sku WHERE id = ?", Integer.class, skuId)).isOne();
    }

    /**
     * 规则：几个 Java 之外再兜一层的约束。迁移合进 main 以后一个字都不能改，这里先在真库上试一遍：
     * 同一物料同色同规格只有一个 SKU（颜色、规格都为空也算重复）；工厂仓有且只有它绑工厂；
     * 价格的类型和必填列对得上；设计师编号全表唯一，删了的人的也占着
     */
    @Test
    void batch2Constraints() {
        long skuId = materialSku(null);
        long materialId = jdbc.queryForObject("SELECT material_id FROM bas_material_sku WHERE id = ?", Long.class, skuId);
        assertThat(sqlState(catchThrowableOfType(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO bas_material_sku (material_id, code) VALUES (?, ?)", materialId, "IT-" + random(8)))))
                .as("同一物料第二个不带颜色、规格的 SKU").isEqualTo("23505");

        assertThat(sqlState(catchThrowableOfType(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO bas_warehouse (code, name, type) VALUES (?, '测试', 'FACTORY')", "IT" + random(8)))))
                .as("没绑工厂的工厂仓").isEqualTo("23514");
        long supplierId = insertId("INSERT INTO sup_supplier (code, name, short_name) VALUES (?, '测试供应商', '测试')",
                "IT" + random(8));
        cleanUps.push("DELETE FROM sup_supplier WHERE id = " + supplierId);
        assertThat(sqlState(catchThrowableOfType(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO bas_warehouse (code, name, type, factory_id) VALUES (?, '测试', 'MATERIAL', ?)",
                "IT" + random(8), supplierId))))
                .as("绑了工厂的物料仓").isEqualTo("23514");

        assertThat(sqlState(catchThrowableOfType(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO prc_price (type, supplier_id, material_id, process_mode, price, unit, tax_pct, effective_date)
                VALUES ('PROCESS', ?, ?, 'CMT', 1, 'PIECE', 13, CURRENT_DATE)
                """, supplierId, materialId))))
                .as("加工价带了物料、没带款式").isEqualTo("23514");
        long priceId = insertId("""
                INSERT INTO prc_price (type, supplier_id, material_id, material_sku_id, price, unit, tax_pct, effective_date)
                VALUES ('MATERIAL', ?, ?, ?, 18.5, 'METER', 13, CURRENT_DATE)
                """, supplierId, materialId, skuId);
        cleanUps.push("DELETE FROM prc_price WHERE id = " + priceId);

        String designerNo = String.valueOf(ThreadLocalRandom.current().nextInt(10, 100));
        long left = insertId("INSERT INTO sys_user (username, password, nickname, designer_no, deleted) VALUES (?, 'x', '离职的', ?, true)",
                "it_" + random(8), designerNo);
        cleanUps.push("DELETE FROM sys_user WHERE id = " + left);
        assertThat(sqlState(catchThrowableOfType(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO sys_user (username, password, nickname, designer_no) VALUES (?, 'x', '新来的', ?)",
                "it_" + random(8), designerNo))))
                .as("离职的人的设计师编号").isEqualTo("23505");
    }

    // ---------------- 造数据 ----------------

    private long color() {
        long id = insertId("INSERT INTO bas_color (code, name) VALUES (?, ?)", random(3), "测试色" + random(8));
        cleanUps.push("DELETE FROM bas_color WHERE id = " + id);
        return id;
    }

    /** 一级分类 → 二级分类 → 物料 → 一个 SKU。colorId 为空就是不带颜色的 SKU */
    private long materialSku(Long colorId) {
        String name = "测试" + random(8);
        long parent = insertId("INSERT INTO bas_material_category (name, level) VALUES (?, 1)", name);
        cleanUps.push("DELETE FROM bas_material_category WHERE id = " + parent);
        long category = insertId("INSERT INTO bas_material_category (parent_id, code, name, level) VALUES (?, ?, ?, 2)",
                parent, unusedCategoryCode(), name);
        cleanUps.push("DELETE FROM bas_material_category WHERE id = " + category);
        String code = "IT" + random(8);
        long material = insertId("""
                INSERT INTO bas_material (code, name, category_id, material_type, stock_unit, purchase_unit)
                VALUES (?, ?, ?, 'FABRIC', 'METER', 'METER')
                """, code, name, category);
        cleanUps.push("DELETE FROM bas_material WHERE id = " + material);
        long sku = insertId("INSERT INTO bas_material_sku (material_id, code, color_id) VALUES (?, ?, ?)",
                material, code + "-" + random(3), colorId);
        cleanUps.push("DELETE FROM bas_material_sku WHERE id = " + sku);
        return sku;
    }

    /** 物料分类码只有 2 位字母、全表唯一，随机取一个还没人用的 */
    private String unusedCategoryCode() {
        while (true) {
            String code = Stream.generate(() -> String.valueOf((char) ('A' + ThreadLocalRandom.current().nextInt(26))))
                    .limit(2).collect(Collectors.joining());
            Integer used = jdbc.queryForObject("SELECT count(*) FROM bas_material_category WHERE code = ?", Integer.class, code);
            if (used == 0) {
                return code;
            }
        }
    }

    private long insertId(String sql, Object... args) {
        return jdbc.queryForObject(sql + " RETURNING id", Long.class, args);
    }

    private List<String> tables() {
        return jdbc.queryForList("""
                SELECT relname FROM pg_class
                WHERE relnamespace = current_schema()::regnamespace AND relkind IN ('r', 'p')
                """, String.class);
    }

    private List<String> foreignKeyCount() {
        return jdbc.queryForList(
                "SELECT conname::text FROM pg_constraint WHERE contype = 'f' AND connamespace = current_schema()::regnamespace",
                String.class);
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String random(int length) {
        return UUID.randomUUID().toString().replace("-", "").substring(0, length).toUpperCase();
    }

    private static String inList(Set<String> codes) {
        return codes.stream().map(code -> "'" + code + "'").collect(Collectors.joining(", "));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        return Stream.of(sets).flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
