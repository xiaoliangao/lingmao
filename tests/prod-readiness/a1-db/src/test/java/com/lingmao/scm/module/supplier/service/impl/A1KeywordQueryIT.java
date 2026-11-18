package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.basic.service.MaterialCategoryService;
import com.lingmao.scm.module.basic.service.SizeGroupService;
import com.lingmao.scm.module.inventory.dto.CountCreateReq;
import com.lingmao.scm.module.inventory.dto.CountQuery;
import com.lingmao.scm.module.inventory.dto.StockQuery;
import com.lingmao.scm.module.inventory.mapper.InvCountMapper;
import com.lingmao.scm.module.inventory.mapper.StockMapper;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import com.lingmao.scm.module.inventory.mapper.StockLogMapper;
import com.lingmao.scm.module.inventory.service.CountService;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.dto.MaterialPickQuery;
import com.lingmao.scm.module.inventory.dto.InvDocQuery;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.inventory.mapper.InvDocMapper;
import com.lingmao.scm.module.basic.mapper.MaterialMapper;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.techpack.dto.StyleCostQuery;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.mapper.ProductionOrderMapper;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.MrpCalcQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderMapper;
import com.lingmao.scm.module.purchase.mapper.MrpCalcMapper;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.mapper.StyleMapper;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #12/T07：实际 Mapper 执行后的 COUNT/分页 SQL，十万主档与真实外键关联，不关闭顺序扫描。 */
@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class A1KeywordQueryIT {
    private static final long BASE = 8_010_000_000_000_000L;
    private static final int SIZE = 100_000;
    private static final Pattern BULK_INSERT_TABLE = Pattern.compile("\\A\\s*INSERT\\s+INTO\\s+([a-z][a-z0-9_]*)\\s*\\(");
    private static final Path ARTIFACTS = Path.of("target", "a1-production", "keyword-plans");
    private static final Set<String> LARGE_TABLES = Set.of("bas_material", "bas_material_sku", "sty_style", "sty_style_sku",
            "ord_order", "ord_order_item", "ord_change", "ord_change_item", "sup_supplier", "mrp_calc", "mrp_calc_item", "pur_order", "pur_order_item",
            "prd_order", "prd_order_item", "tp_tech_pack", "inv_stock_log", "inv_doc", "inv_doc_item", "inv_stock", "inv_count", "inv_count_item");

    @Autowired JdbcTemplate jdbc;
    @Autowired SqlSessionFactory sessions;
    @Autowired JsonMapper json;
    @Autowired MaterialMapper materials;
    @Autowired StyleMapper styles;
    @Autowired OrderMapper orders;
    @Autowired OrderChangeMapper changes;
    @Autowired TechPackMapper packs;
    @Autowired PurchaseOrderMapper purchases;
    @Autowired MrpCalcMapper mrp;
    @Autowired ProductionOrderMapper production;
    @Autowired SupplierMapper suppliers;
    @Autowired SupplierLists supplierLists;
    @Autowired StockLogMapper stockLogs;
    @Autowired InvDocMapper inventoryDocuments;
    @Autowired InvCountMapper inventoryCounts;
    @Autowired StockMapper inventoryStocks;
    @Autowired ProductionReceiptService receipts;
    @Autowired WarehouseService warehouseService;
    @Autowired MaterialCategoryService categoryService;
    @Autowired CountService counts;
    @Autowired StyleService styleService;
    @Autowired SizeGroupService sizeGroupService;
    private final SoftAssertions planAssertions = new SoftAssertions();
    private final Map<String, Object> planSummary = new LinkedHashMap<>();
    private long user;
    private long company;
    private long warehouse;
    private long materialCategory;
    private long styleCategory;
    private long sizeGroup;
    private long color;

    @BeforeAll
    void seedRealRelations() throws Exception {
        Files.createDirectories(ARTIFACTS);
        String suffix = UUID.randomUUID().toString();
        user = id("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','A1 query') RETURNING id", "kw-" + suffix);
        company = id("INSERT INTO bas_company(code,name,short_name) VALUES (?,'A1 query','A1 query') RETURNING id", "KW" + user);
        TestIdentity.grant(jdbc, user, List.of("data:all", "production:receipt:add"));
        warehouse = id("INSERT INTO bas_warehouse(code,name,type) VALUES (?,'A1 query','MATERIAL') RETURNING id", "KW" + user);
        materialCategory = id("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", "A1 query " + suffix);
        styleCategory = id("INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id", "A1 query " + suffix);
        sizeGroup = id("INSERT INTO bas_size_group(name) VALUES (?) RETURNING id", "A1 query " + suffix);
        color = TestColors.insert(jdbc, "前缀needleColor后缀" + user);
        bulk("""
                INSERT INTO sup_supplier(id,code,name,short_name,status) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,'ZS'||lpad(n::text,6,'0'),
                       CASE WHEN n=53210 THEN '杭州needleSupplier工厂' ELSE '供应商'||n END,'厂家'||n,'ACTIVE'
                FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO bas_material(id,code,name,category_id,material_type,stock_unit,purchase_unit,supplier_item_no)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZM'||lpad(n::text,6,'0'),
                CASE WHEN n=53210 THEN '背景needleFabric内衬' ELSE '面料'||n END,?,'FABRIC','METER','METER',
                'HW'||lpad(n::text,6,'0') FROM generate_series(1,100000) n
                """, materialCategory);
        bulk("""
                INSERT INTO bas_material_sku(id,material_id,code,color_id) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,'ZX'||lpad(n::text,6,'0'),CASE WHEN n=53210 THEN ? ELSE NULL END
                FROM generate_series(1,100000) n
                """, color);
        bulk("""
                INSERT INTO sty_style(id,code,name,style_class,brand,category_id,year,season,source,size_group_id,status)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZT'||lpad(n::text,6,'0'),
                CASE WHEN n=53210 THEN '秋季needleStyle套装' ELSE '款式'||n END,'FS','A1_QUERY',?,2026,'SPRING','DESIGN',?,'ENABLED'
                FROM generate_series(1,100000) n
                """, styleCategory, sizeGroup);
        bulk("""
                INSERT INTO sty_style_sku(id,style_id,code,color_id,size_code) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,'ZY'||lpad(n::text,6,'0'),?,'M' FROM generate_series(1,100000) n
                """, color);
        bulk("""
                INSERT INTO tp_tech_pack(id,style_id,stage,version,status) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,'PROD',1,'EFFECTIVE' FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO ord_order(id,no,contract_no,company_id,order_type,order_date,required_date,sales_id,merch_id,create_by,status,total_qty)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZO'||lpad(n::text,6,'0'),'ZC'||lpad(n::text,6,'0'),?,
                'OWN','2026-01-01','2026-12-31',?,?,?,'APPROVED',1 FROM generate_series(1,100000) n
                """, company, user, user, user);
        bulk("""
                INSERT INTO ord_order_item(id,order_id,seq,style_id,style_sku_id,qty,tech_pack_id) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,1,@base@+n,@base@+n,1,@base@+n FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO ord_change(id,no,company_id,order_id,change_types,reason,status,create_by)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZH'||lpad(n::text,6,'0'),?,@base@+n,'QTY','A1 query','APPROVED',?
                FROM generate_series(1,100000) n
                """, company, user);
        bulk("""
                INSERT INTO ord_change_item(id,change_id,seq,change_type,order_item_id,style_id,style_sku_id,from_qty,to_qty)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,@base@+n,1,'QTY',@base@+n,@base@+n,@base@+n,1,2
                FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO mrp_calc(id,no,company_id,status,create_by) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,'ZR'||lpad(n::text,6,'0'),?,'CONFIRMED',? FROM generate_series(1,100000) n
                """, company, user);
        bulk("""
                INSERT INTO mrp_calc_order(calc_id,order_id)
                SELECT @base@+n,@base@+n FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO mrp_calc_item(id,calc_id,seq,material_sku_id,gross_qty,need_qty,net_qty,order_nos,style_codes)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,@base@+n,1,@base@+n,1,1,1,
                'ZO'||lpad(n::text,6,'0')||',FZ'||lpad(n::text,6,'0'),'ZT'||lpad(n::text,6,'0')
                FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO pur_order(id,no,company_id,supplier_id,purchase_type,calc_id,buyer_id,order_date,delivery_date,warehouse_id,status,create_by,total_qty)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZP'||lpad(n::text,6,'0'),?,@base@+n,'ORDER',@base@+n,?,
                '2026-01-01','2026-12-31',?,'APPROVED',?,1 FROM generate_series(1,100000) n
                """, company, user, warehouse, user);
        bulk("""
                INSERT INTO pur_order_item(id,purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount,calc_item_id)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,@base@+n,1,@base@+n,'METER',1,1,1,1,@base@+n
                FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO mrp_calc_item(id,calc_id,seq,material_sku_id,gross_qty,need_qty,net_qty,use_transit_qty,order_nos)
                OVERRIDING SYSTEM VALUE VALUES (@base@+100001,@base@+99999,2,@base@+53210,1,1,0,1,'TRAFZ-ONLY')
                """);
        bulk("INSERT INTO mrp_calc_transit(calc_item_id,purchase_item_id,qty) VALUES (@base@+100001,@base@+53210,1)");
        bulk("""
                INSERT INTO prd_order(id,no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,order_date,due_date,
                                      unit_price,tax_pct,merch_id,settle_method,status,create_by,total_qty)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZD'||lpad(n::text,6,'0'),?,'OUTSOURCE',@base@+n,'CMT',
                @base@+n,@base@+n,@base@+n,'2026-01-01','2026-12-31',1,13,?,'MONTHLY','APPROVED',?,1
                FROM generate_series(1,100000) n
                """, company, user, user);
        bulk("""
                INSERT INTO prd_order_item(id,prd_order_id,seq,order_item_id,style_sku_id,qty) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,1,@base@+n,@base@+n,1 FROM generate_series(1,100000) n
                """);
        bulk("""
                INSERT INTO inv_doc(id,no,company_id,kind,doc_type,status,to_warehouse_id,doc_date,create_by,source_no,supplier_delivery_no,logistics_no)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZL'||lpad(n::text,6,'0'),?,'IN','OTHER_IN','APPROVED',?,
                '2026-01-01',?,'DOCSRC'||lpad(n::text,6,'0'),'DOCDEL'||lpad(n::text,6,'0'),'DOCLOG'||lpad(n::text,6,'0')
                FROM generate_series(1,100000) n
                """, company, warehouse, user);
        bulk("""
                INSERT INTO inv_doc_item(id,doc_id,seq,material_sku_id,qty,price,amount) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,@base@+n,1,@base@+n,1,1,1 FROM generate_series(1,100000) n
                """);
        jdbc.update("UPDATE inv_doc_item SET dye_lot='前缀needleDye后缀' WHERE id=?", BASE + 53210);
        bulk("""
                INSERT INTO inv_stock_log(id,inv_doc_id,inv_doc_item_id,change_type,doc_no,biz_date,warehouse_id,material_sku_id,
                                          change_qty,balance_qty,create_time,create_by,dye_lot)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,@base@+n,@base@+n,'OTHER_IN','ZL'||lpad(n::text,6,'0'),
                '2026-01-01',?,@base@+n,1,1,'2026-01-01 00:00:00+00'::timestamptz+n*interval '1 second',?,
                CASE WHEN n=53210 THEN '前缀needleDye后缀' ELSE '' END FROM generate_series(1,100000) n
                """, warehouse, user);
        bulk("""
                INSERT INTO inv_stock(id,warehouse_id,material_sku_id,qty) OVERRIDING SYSTEM VALUE
                SELECT @base@+n,?,@base@+n,1 FROM generate_series(1,100000) n
                """, warehouse);
        bulk("""
                INSERT INTO inv_count(id,no,company_id,warehouse_id,count_date,counter_id,status,create_by)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,'ZN'||lpad(n::text,6,'0'),?,?,
                '2026-01-01',?,'APPROVED',? FROM generate_series(1,100000) n
                """, company, warehouse, user, user);
        bulk("""
                INSERT INTO inv_count_item(id,count_id,seq,material_sku_id,book_qty,counted_qty)
                OVERRIDING SYSTEM VALUE SELECT @base@+n,@base@+n,1,@base@+n,1,1 FROM generate_series(1,100000) n
                """);
        for (String table : LARGE_TABLES) jdbc.execute("ANALYZE " + table);
        jdbc.execute("ANALYZE mrp_calc_order");
        jdbc.execute("ANALYZE mrp_calc_transit");
    }

    @BeforeEach
    void login() { TestIdentity.company(company); login(user, true); }

    @AfterAll
    void removeLargeFixture() {
        TestIdentity.clear();
        jdbc.update("DELETE FROM prd_receipt_item WHERE receipt_id IN (SELECT id FROM prd_receipt WHERE prd_order_id>? AND prd_order_id<=?)", BASE, BASE + SIZE);
        jdbc.update("DELETE FROM prd_receipt WHERE prd_order_id>? AND prd_order_id<=?", BASE, BASE + SIZE);
        jdbc.update("DELETE FROM mrp_calc_order WHERE calc_id>? AND calc_id<=?", BASE, BASE + SIZE);
        jdbc.update("DELETE FROM mrp_calc_transit WHERE calc_item_id>? AND calc_item_id<=?", BASE, BASE + SIZE + 10);
        for (String table : List.of("inv_stock_log", "inv_doc_item", "inv_doc", "inv_stock", "inv_count_item", "inv_count", "prd_order_item", "prd_order", "pur_order_item", "pur_order", "mrp_calc_item", "mrp_calc",
                "ord_change_item", "ord_change", "ord_order_item", "ord_order", "tp_tech_pack", "sty_style_sku", "sty_style", "bas_material_sku", "bas_material", "sup_supplier")) {
            // 外键逐行检查也受生产 statement_timeout 约束；分小事务清理，不调整数据库超时或关闭约束。
            for (long from = BASE; from < BASE + SIZE + 10; from += 1000) {
                jdbc.update("DELETE FROM " + table + " WHERE id>? AND id<=?", from, Math.min(from + 1000, BASE + SIZE + 10));
            }
            jdbc.execute("ANALYZE " + table);
        }
    }

    @Test
    void t07_actualKeywordCountAndPagePlansUseSelectiveIndexesOn100000Rows() throws Exception {
        Map<String, Function<String, Result>> queries = new LinkedHashMap<>();
        queries.put("material", this::material);
        queries.put("style", this::style);
        queries.put("order", this::order);
        queries.put("purchase", this::purchase);
        queries.put("production", this::production);
        queries.put("supplier", this::supplier);
        Map<String, String> prefixes = Map.of("material", "ZM", "style", "ZT", "order", "ZO", "purchase", "ZP", "production", "ZD", "supplier", "ZS");
        Map<String, String> indexes = Map.of("material", "idx_bas_mat_code", "style", "idx_sty_style_code",
                "order", "idx_ord_order_no", "purchase", "idx_pur_order_no", "production", "idx_prd_order_no", "supplier", "idx_sup_supplier_code");
        for (var entry : queries.entrySet()) {
            String key = entry.getKey();
            String codePrefix = prefixes.get(key);
            explain(key + "-contains", () -> entry.getValue().apply(codePrefix + "05321"), 10, indexes.get(key) + "_trgm");
            String hundred = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> codePrefix + String.format("%06d", n)).toList());
            explain(key + "-100-exact-codes", () -> entry.getValue().apply(hundred), 100, indexes.get(key) + "_upper");
            assertThat(entry.getValue().apply(codePrefix + "0532," + codePrefix + "053210").ids()).containsExactly(BASE + 53210);
        }
        explain("material-name", () -> material("needleFabric"), 1, "idx_bas_mat_text_trgm");
        explain("style-name", () -> style("needleStyle"), 1, "idx_sty_style_text_trgm");
        explain("supplier-name", () -> supplier("needleSupplier"), 1, "idx_sup_supplier_text_trgm");
        explain("order-contract", () -> order("ZC05321"), 10, "idx_ord_order_contract_trgm");
        explain("order-related-style", () -> order("ZT05321"), 10, "idx_sty_style_code_trgm");
        explain("material-supplier-item", () -> material("HW05321"), 10, "idx_bas_mat_item_trgm");
        explain("purchase-related-sku", () -> purchase("ZX05321"), 10, "idx_bas_mat_sku_code_trgm");
        explain("purchase-related-material", () -> purchase("ZM05321"), 10, "idx_bas_mat_code_trgm");
        explain("purchase-related-material-name", () -> purchase("needleFabric"), 1, "idx_bas_mat_text_trgm");
        explain("purchase-related-supplier", () -> purchase("needleSupplier"), 1, "idx_sup_supplier_text_trgm");
        explain("purchase-related-mrp", () -> purchase("ZR05321"), 10, "idx_mrp_calc_no_trgm");
        explain("purchase-related-transit", () -> purchase("TRAFZ"), 1, "idx_mrp_item_snapshot_search");
        explain("purchase-snapshot-second-token", () -> purchase("FZ05321"), 10, "idx_mrp_item_snapshot_search");
        explain("purchase-snapshot-style", () -> purchase("ZT05321"), 10, "idx_mrp_item_snapshot_search");
        String snapshots = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "FZ" + String.format("%06d", n)).toList());
        explain("purchase-snapshot-100-exact", () -> purchase(snapshots), 100, "idx_mrp_item_snapshot_search");
        explain("production-related-order", () -> production("ZO05321"), 10, "idx_ord_order_no_trgm");
        explain("production-related-style", () -> production("ZT05321"), 10, "idx_sty_style_code_trgm");
        explain("production-related-style-name", () -> production("needleStyle"), 1, "idx_sty_style_text_trgm");
        explain("production-related-factory", () -> production("needleSupplier"), 1, "idx_sup_supplier_text_trgm");
        explain("mrp-contains", () -> mrp("ZR05321"), 10, "idx_mrp_calc_no_trgm");
        explain("mrp-related-order", () -> mrp("ZO05321"), 10, "idx_ord_order_no_trgm");
        explain("mrp-related-style", () -> mrp("ZT05321"), 10, "idx_sty_style_code_trgm");
        explain("seek-doc-contains", () -> seek("ZL05321"), 10, "idx_inv_log_doc_trgm", 1);
        explain("seek-sku-contains", () -> seek("ZX05321"), 10, "idx_bas_mat_sku_code_trgm", 1);
        explain("seek-material-name", () -> seek("needleFabric"), 1, "idx_bas_mat_text_trgm", 1);
        explain("seek-dye-lot", () -> seek("needleDye"), 1, "idx_inv_log_dye_trgm", 1);
        String logCodes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZL" + String.format("%06d", n)).toList());
        explain("seek-100-exact-codes", () -> seek(logCodes), 100, "idx_inv_log_doc_upper", 1);
        explain("inventory-doc-contains", () -> inventoryDocument("ZL05321"), 10, "idx_inv_doc_no_trgm");
        explain("inventory-doc-material-code", () -> inventoryDocument("ZM05321"), 10, "idx_bas_mat_code_trgm");
        explain("inventory-doc-material-name", () -> inventoryDocument("needleFabric"), 1, "idx_bas_mat_text_trgm");
        explain("inventory-doc-source-no", () -> inventoryDocument("DOCSRC053210"), 1, "idx_inv_doc_source_trgm");
        explain("inventory-doc-delivery-no", () -> inventoryDocument("DOCDEL053210"), 1, "idx_inv_doc_delivery_trgm");
        explain("inventory-doc-logistics-no", () -> inventoryDocument("DOCLOG053210"), 1, "idx_inv_doc_logistics_trgm");
        explain("inventory-doc-dye-lot", () -> inventoryDocument("needleDye"), 1, "idx_inv_doc_item_dye_trgm");
        explain("inventory-doc-100-exact-codes", () -> inventoryDocument(logCodes), 100, "idx_inv_doc_no_upper");
        explain("sku-picker-code", () -> pickSku("ZX05321"), 10, "idx_bas_mat_sku_code_trgm");
        explain("sku-picker-material-name", () -> pickSku("needleFabric"), 1, "idx_bas_mat_text_trgm");
        explain("sku-picker-color", () -> pickSku("needleColor"), 1, "idx_bas_mat_sku_color");
        explain("count-doc-contains", () -> inventoryCount("ZN05321"), 10, "idx_inv_count_no_trgm");
        explain("count-material-code", () -> inventoryCount("ZM05321"), 10, "idx_bas_mat_code_trgm");
        explain("count-material-name", () -> inventoryCount("needleFabric"), 1, "idx_bas_mat_text_trgm");
        String countCodes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZN" + String.format("%06d", n)).toList());
        explain("count-100-exact-codes", () -> inventoryCount(countCodes), 100, "idx_inv_count_no_upper");
        String skuCodes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZX" + String.format("%06d", n)).toList());
        for (boolean summary : List.of(false, true)) {
            String prefix = summary ? "stock-summary-" : "stock-row-";
            explain(prefix + "sku-code", () -> inventoryStock("ZX05321", summary), 10, "idx_bas_mat_sku_code_trgm");
            explain(prefix + "material-code", () -> inventoryStock("ZM05321", summary), 10, "idx_bas_mat_code_trgm");
            explain(prefix + "material-name", () -> inventoryStock("needleFabric", summary), 1, "idx_bas_mat_text_trgm");
            explain(prefix + "100-exact-codes", () -> inventoryStock(skuCodes, summary), 100, "idx_bas_mat_sku_code_upper");
        }
        explain("order-change-no", () -> orderChange("ZH05321"), 10, "idx_ord_change_no_trgm");
        explain("order-change-related-order", () -> orderChange("ZO05321"), 10, "idx_ord_order_no_trgm");
        explain("order-change-related-sku", () -> orderChange("ZY05321"), 10, "idx_sty_sku_code_trgm");
        String changeCodes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZH" + String.format("%06d", n)).toList());
        explain("order-change-100-exact-codes", () -> orderChange(changeCodes), 100, "idx_ord_change_no_upper");
        explain("techpack-style-code", () -> styleCost("ZT05321"), 10, "idx_sty_style_code_trgm");
        explain("techpack-style-name", () -> styleCost("needleStyle"), 1, "idx_sty_style_text_trgm");
        String styleCodes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZT" + String.format("%06d", n)).toList());
        explain("techpack-100-exact-codes", () -> styleCost(styleCodes), 100, "idx_sty_style_code_upper");
        Files.writeString(ARTIFACTS.resolve("summary.json"), json.writeValueAsString(planSummary));
        planAssertions.assertAll();
    }

    @Test
    void t07_literalWildcardsAndOuterVisibilityFiltersAreNotBypassedByCandidates() {
        jdbc.update("UPDATE bas_material SET code=?,name=? WHERE id=?", "LIT%_\\CODE", "含100%_\\天然纤维", BASE + 99999);
        jdbc.update("UPDATE bas_material SET code=?,name=? WHERE id=?", "LITxxYCODE", "含100xxY天然纤维", BASE + 99998);
        String unicodeBoundary = "🧵".repeat(100); // Java UTF-16 长 200；PG varchar(100) 是 100 个字符。
        jdbc.update("UPDATE sup_supplier SET name=? WHERE id=?", unicodeBoundary, BASE + 99999);
        try {
            assertThat(material("LIT%_\\C").ids()).containsExactly(BASE + 99999);
            assertThat(material("100%_\\").ids()).containsExactly(BASE + 99999);
            assertThat(material("M053210").ids()).containsExactly(BASE + 53210); // 编码按包含查，中间的子串也能找到。
            assertThat(supplier(unicodeBoundary).ids()).containsExactly(BASE + 99999);
            assertThat(supplier(unicodeBoundary + "🧵").ids()).isEmpty();
            OrderQuery dated = new OrderQuery();
            dated.setKeyword("ZT05321"); dated.setOrderDateFrom(LocalDate.of(2027, 1, 1));
            assertThat(orders.selectRowPage(new Page<>(1, 100), dated, null, DataScope.current(false)).getTotal()).isZero();
            PurchaseQuery status = new PurchaseQuery();
            status.setKeyword("needleFabric"); status.setStatus(List.of(DocStatus.DRAFT));
            assertThat(purchases.selectRowPage(new Page<>(1, 100), status, null, DataScope.current(false)).getTotal()).isZero();
            CountQuery countDate = new CountQuery(); countDate.setKeyword("ZM05321"); countDate.setCountDateFrom(LocalDate.of(2027, 1, 1));
            assertThat(inventoryCounts.selectRowPage(new Page<>(1, 100), countDate, null, DataScope.current(false)).getTotal()).isZero();
            InvDocQuery docDate = new InvDocQuery(); docDate.setKind(InvDocKind.IN); docDate.setKeyword("ZM05321"); docDate.setDocDateFrom(LocalDate.of(2027, 1, 1));
            assertThat(inventoryDocuments.selectRowPage(new Page<>(1, 100), docDate, null, DataScope.current(false)).getTotal()).isZero();
            StockQuery stockWarehouse = new StockQuery(); stockWarehouse.setKeyword("needleFabric"); stockWarehouse.setWarehouseId(Long.MAX_VALUE);
            assertThat(inventoryStocks.selectRowPage(new Page<>(1, 100), stockWarehouse, null).getTotal()).isZero();
            login(user + 999999, false);
            assertThat(order("ZT05321").ids()).isEmpty();
            assertThat(purchase("needleSupplier").ids()).isEmpty();
            assertThat(production("needleStyle").ids()).isEmpty();
            assertThat(inventoryDocument("needleFabric").ids()).isEmpty();
            assertThat(inventoryCount("ZM05321").ids()).isEmpty();
            assertThat(orderChange("ZY05321").ids()).isEmpty();
        } finally {
            login(user, true);
            jdbc.update("UPDATE bas_material SET code='ZM099999',name='面料99999' WHERE id=?", BASE + 99999);
            jdbc.update("UPDATE bas_material SET code='ZM099998',name='面料99998' WHERE id=?", BASE + 99998);
            jdbc.update("UPDATE sup_supplier SET name='供应商99999' WHERE id=?", BASE + 99999);
        }
    }

    @Test
    void t07_pendingArrivalsKeepItemSemanticsAndHistoricalSnapshotTokens() {
        long extra = BASE + SIZE + 1;
        jdbc.update("""
                INSERT INTO pur_order_item(id,purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                OVERRIDING SYSTEM VALUE VALUES (?,?,2,?,'METER',1,1,1,1)
                """, extra, BASE + 53210, BASE + 53211);
        try {
            PendingArrivalQuery q = new PendingArrivalQuery(); q.setKeyword("ZM053210");
            assertThat(purchases.selectPendingRows(q, null, DataScope.current(false)))
                    .extracting(row -> row.getPurchaseItemId()).containsExactly(BASE + 53210);
            q.setKeyword("ZP053210");
            assertThat(purchases.selectPendingRows(q, null, DataScope.current(false)))
                    .extracting(row -> row.getPurchaseItemId()).containsExactlyInAnyOrder(BASE + 53210, extra);
            q.setKeyword("FZ053210"); // 不是当前订单表编码，也不是快照的第一个 token。
            assertThat(purchases.selectPendingRows(q, null, DataScope.current(false)))
                    .extracting(row -> row.getPurchaseItemId()).containsExactly(BASE + 53210);
            q.setKeyword("Z053210"); // 按包含查：第二个 token 中间的片段也能找到。
            assertThat(purchases.selectPendingRows(q, null, DataScope.current(false)))
                    .extracting(row -> row.getPurchaseItemId()).containsExactly(BASE + 53210);
        } finally { jdbc.update("DELETE FROM pur_order_item WHERE id=?", extra); }
    }

    @Test
    void t07_receiptListUsesOneWarehouseLookupForOneAndFiftyActualReceipts() throws Exception {
        long prd = BASE + 90000;
        jdbc.update("UPDATE ord_order SET total_qty=50 WHERE id=?", prd);
        jdbc.update("UPDATE ord_order_item SET qty=50,scheduled_qty=50 WHERE id=?", prd);
        jdbc.update("UPDATE prd_order SET total_qty=50 WHERE id=?", prd);
        jdbc.update("UPDATE prd_order_item SET qty=50 WHERE id=?", prd);
        List<Long> warehouseIds = new ArrayList<>();
        List<String> small = List.of();
        for (int n = 1; n <= 50; n++) {
            long wh = id("INSERT INTO bas_warehouse(code,name,type,keeper_id) VALUES (?,?,'PRODUCT',?) RETURNING id",
                    "A1-RW-" + user + "-" + n, "回货仓-" + n, user);
            warehouseIds.add(wh);
            ReceiptReq req = new ReceiptReq(); req.setClientActionId(UUID.randomUUID());
            req.setReceiptDate(LocalDate.of(2026, 1, 1)); req.setWarehouseId(wh);
            ReceiptReq.Item line = new ReceiptReq.Item(); line.setPrdOrderItemId(prd); line.setQty(1);
            req.setItems(List.of(line)); receipts.receive(prd, req);
            if (n == 1) {
                receipts.list(prd); // 与 50 行比较前相同地预热只读缓存。
                small = captureSelects(() -> assertThat(receipts.list(prd)).hasSize(1));
            }
        }
        List<String> large = captureSelects(() -> assertThat(receipts.list(prd)).hasSize(50)
                .allSatisfy(row -> assertThat(row.getWarehouseName()).startsWith("回货仓-")));
        assertThat(large).hasSize(small.size());
        assertThat(large.size()).isLessThanOrEqualTo(14);
        assertThat(large.stream().filter(sql -> sql.contains("bas_warehouse")).toList()).singleElement()
                .satisfies(sql -> assertThat(sql).doesNotContain("keeper_id"));
        assertThatThrownBy(() -> warehouseService.names(List.of(warehouseIds.getFirst(), Long.MAX_VALUE)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        ReceiptReq noWarehouse = new ReceiptReq(); noWarehouse.setClientActionId(UUID.randomUUID());
        noWarehouse.setReceiptDate(LocalDate.of(2026, 1, 1));
        ReceiptReq.Item optionalLine = new ReceiptReq.Item(); optionalLine.setPrdOrderItemId(BASE + 89999); optionalLine.setQty(1);
        noWarehouse.setItems(List.of(optionalLine)); receipts.receive(BASE + 89999, noWarehouse);
        assertThat(receipts.list(BASE + 89999)).singleElement().satisfies(row -> assertThat(row.getWarehouseName()).isNull());
        Files.writeString(ARTIFACTS.resolve("receipt-query-count.json"), json.writeValueAsString(Map.of("one", small, "fifty", large)));
    }

    @Test
    void t07_countCreationValidatesOneAndFiftyCategoriesWithFixedReads() throws Exception {
        List<Long> categories = jdbc.queryForList("""
                INSERT INTO bas_material_category(parent_id,code,name,level)
                SELECT ?,x.code,'A1批验-'||x.code,2 FROM (
                    SELECT chr(65+a)||chr(65+b) AS code FROM generate_series(0,25) a CROSS JOIN generate_series(0,25) b
                ) x WHERE NOT EXISTS (SELECT 1 FROM bas_material_category c WHERE c.code=x.code)
                ORDER BY x.code LIMIT 50 RETURNING id
                """, Long.class, materialCategory);
        List<Long> created = new ArrayList<>();
        try {
            CountCreateReq smallReq = countRequest(categories.subList(0, 1), "ONE");
            CountCreateReq largeReq = countRequest(categories, "MANY");
            List<String> small = captureSelects(() -> created.add(counts.create(smallReq).id()));
            List<String> large = captureSelects(() -> created.add(counts.create(largeReq).id()));
            assertThat(large).hasSize(small.size());
            assertThat(large.stream().filter(sql -> sql.contains("bas_material_category")).toList()).hasSize(1);
            assertThatThrownBy(() -> categoryService.requireExists(List.of(categories.getFirst(), Long.MAX_VALUE)))
                    .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(404));
            Files.writeString(ARTIFACTS.resolve("count-category-query-count.json"), json.writeValueAsString(Map.of("one", small, "fifty", large)));
        } finally {
            created.forEach(counts::delete);
            jdbc.update("DELETE FROM bas_material_category WHERE parent_id=? AND name LIKE 'A1批验-%'", materialCategory);
        }
    }

    @Test
    void t07_styleMatrixLoadsOneAndFiftySizeGroupsWithFixedReads() throws Exception {
        List<Long> styleIds = new ArrayList<>();
        for (int n = 1; n <= 50; n++) {
            long group = id("INSERT INTO bas_size_group(name,base_size) VALUES (?,'M') RETURNING id", "A1 batch " + user + "-" + n);
            jdbc.update("INSERT INTO bas_size_group_item(size_group_id,size_code,seq) VALUES (?,'M',1)", group);
            long style = BASE + 90000 + n; styleIds.add(style);
            jdbc.update("UPDATE sty_style SET size_group_id=? WHERE id=?", group, style);
        }
        List<String> small = captureSelects(() -> assertThat(styleService.orderInfos(styleIds.subList(0, 1))).hasSize(1));
        List<String> large = captureSelects(() -> assertThat(styleService.orderInfos(styleIds)).hasSize(50)
                .allSatisfy((id, info) -> assertThat(info.sizes()).containsExactly("M")));
        assertThat(large).hasSize(small.size()).hasSize(7);
        assertThatThrownBy(() -> sizeGroupService.getByIds(List.of(sizeGroup, Long.MAX_VALUE)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(404));
        Files.writeString(ARTIFACTS.resolve("style-size-query-count.json"), json.writeValueAsString(Map.of("one", small, "fifty", large)));
    }

    private CountCreateReq countRequest(List<Long> categories, String suffix) {
        long wh = id("INSERT INTO bas_warehouse(code,name,type) VALUES (?,'A1批验仓','MATERIAL') RETURNING id", "A1-COUNT-" + user + suffix);
        CountCreateReq req = new CountCreateReq(); req.setCompanyId(company); req.setWarehouseId(wh);
        req.setPurpose(CountPurpose.DAILY); req.setScope(CountScope.CATEGORY); req.setCategoryIds(categories);
        req.setCountDate(LocalDate.of(2026, 1, 1)); req.setCounterId(user); return req;
    }

    private List<String> captureSelects(Runnable query) {
        var captured = SqlStatementCapture.capture(() -> { query.run(); return null; });
        return captured.statements().stream().map(sql -> sql.getSql().strip().replaceAll("\\s+", " "))
                .filter(sql -> sql.regionMatches(true, 0, "SELECT", 0, 6) || sql.regionMatches(true, 0, "WITH", 0, 4)).toList();
    }

    private void explain(String name, Supplier<Result> query, int count, String expectedIndex) throws Exception {
        explain(name, query, count, expectedIndex, 2);
    }

    private void explain(String name, Supplier<Result> query, int count, String expectedIndex, int statements) throws Exception {
        var capture = SqlStatementCapture.capture(query);
        List<BoundSql> captured = capture.statements();
        Result result = capture.result();
        assertThat(result.total()).as(name).isEqualTo(count);
        assertThat(result.ids()).as(name).hasSize(count).doesNotHaveDuplicates();
        int firstMatch = count == 100 ? 1 : 53210;
        assertThat(result.ids()).as(name + " exact fixture matches").containsExactlyInAnyOrderElementsOf(
                IntStream.range(firstMatch, firstMatch + count).mapToObj(n -> BASE + n).toList());
        // 两条都来自真正的 Mapper 调用：分页插件生成的 COUNT 和带 LIMIT 的主查询，未在测试拼接 LIMIT。
        assertThat(captured).as(name + " actual executed SQL").hasSize(statements);
        assertThat(captured.getLast().getSql().toLowerCase()).contains("limit");
        int n = 0;
        for (BoundSql sql : captured) {
            String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
                try (var statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql.getSql())) {
                    var mapped = sessions.getConfiguration().getMappedStatement(MaterialMapper.class.getName() + ".selectListPage");
                    new DefaultParameterHandler(mapped, sql.getParameterObject(), sql).setParameters(statement);
                    try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
                }
            });
            String sample = name + "-" + n++;
            Files.writeString(ARTIFACTS.resolve(sample + ".json"), plan);
            Files.writeString(ARTIFACTS.resolve(sample + "-sql.txt"), sql.getSql());
            List<JsonNode> nodes = nodes(json.readTree(plan).get(0).get("Plan"));
            List<String> indexes = nodes.stream().filter(node -> node.has("Index Name"))
                    .map(node -> node.get("Index Name").asString()).distinct().toList();
            List<String> largeScans = nodes.stream().filter(node -> LARGE_TABLES.contains(node.path("Relation Name").asString()))
                    .filter(node -> (node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong()
                            + node.path("Rows Removed by Index Recheck").asLong()) * node.path("Actual Loops").asLong() >= 1000)
                    .map(node -> node.path("Relation Name").asString() + ": " + node.path("Actual Rows").asInt()
                            + " rows, " + node.path("Rows Removed by Filter").asInt() + " filtered").toList();
            planAssertions.assertThat(indexes).as(sample + " expected selective index").contains(expectedIndex);
            planAssertions.assertThat(largeScans).as(sample + " selective lookup must not scan a large relation").isEmpty();
            planSummary.put(sample, Map.of("expectedMatches", count, "indexes", indexes, "largeRelationScans", largeScans,
                    "executionMs", json.readTree(plan).get(0).get("Execution Time").doubleValue()));
        }
    }

    private Result material(String keyword) {
        MaterialQuery q = new MaterialQuery(); q.setKeyword(keyword);
        return result(materials.selectListPage(new Page<>(1, 100), q, Keywords.codes(keyword)));
    }
    private Result style(String keyword) {
        StyleQuery q = new StyleQuery(); q.setKeyword(keyword);
        return result(styles.selectListPage(new Page<>(1, 100), q, Keywords.codes(keyword), null));
    }
    private Result order(String keyword) {
        OrderQuery q = new OrderQuery(); q.setKeyword(keyword);
        return result(orders.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result orderChange(String keyword) {
        OrderChangeQuery q = new OrderChangeQuery(); q.setKeyword(keyword);
        return result(changes.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result styleCost(String keyword) {
        StyleCostQuery q = new StyleCostQuery(); q.setKeyword(keyword);
        var page = packs.selectStyleCostPage(new Page<>(1, 100), q, Keywords.codes(keyword), null);
        return new Result(page.getTotal(), page.getRecords().stream().map(row -> row.getStyleId()).toList());
    }
    private Result purchase(String keyword) {
        PurchaseQuery q = new PurchaseQuery(); q.setKeyword(keyword);
        return result(purchases.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result production(String keyword) {
        ProductionQuery q = new ProductionQuery(); q.setKeyword(keyword);
        return result(production.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false),
                LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 25)));
    }
    private Result mrp(String keyword) {
        MrpCalcQuery q = new MrpCalcQuery(); q.setKeyword(keyword);
        return result(mrp.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result supplier(String keyword) {
        SupplierQuery q = new SupplierQuery(); q.setKeyword(keyword);
        return result(suppliers.selectPage(new Page<>(1, 100), supplierLists.wrapper(q)));
    }
    /** A2 integration: real async-export SQL must retain the indexed list filters and frozen upper ID. */
    @Test
    void stockExportUsesTheSameIndexedSnapshotFilters() throws Exception {
        explain("export-doc-contains", () -> export("ZL05321"), 10, "idx_inv_log_doc_trgm", 1);
        explain("export-sku-contains", () -> export("ZX05321"), 10, "idx_bas_mat_sku_code_trgm", 1);
        explain("export-material-name", () -> export("needleFabric"), 1, "idx_bas_mat_text_trgm", 1);
        explain("export-dye-lot", () -> export("needleDye"), 1, "idx_inv_log_dye_trgm", 1);
        String codes = String.join(",", IntStream.rangeClosed(1, 100).mapToObj(n -> "ZL" + String.format("%06d", n)).toList());
        explain("export-100-exact-codes", () -> export(codes), 100, "idx_inv_log_doc_upper", 1);
        StockLogQuery query = new StockLogQuery(); query.setKeyword("ZL05321"); query.setWarehouseId(warehouse);
        assertThat(stockLogs.selectExportBatch(query, null, Keywords.codeContains(query.getKeyword()), BASE + 53214, null, null, 500))
                .extracting(row -> row.getId()).containsExactlyInAnyOrderElementsOf(IntStream.range(53210, 53215).mapToObj(n -> BASE + n).toList());
        query.setCreatedDateFrom(LocalDate.of(2027, 1, 1));
        assertThat(stockLogs.selectExportBatch(query, null, Keywords.codeContains(query.getKeyword()), Long.MAX_VALUE, null, null, 500)).isEmpty();
        query.setCreatedDateFrom(null); query.setKeyword("%_");
        assertThat(stockLogs.selectExportBatch(query, null, Keywords.codeContains(query.getKeyword()), Long.MAX_VALUE, null, null, 500)).isEmpty();
        Files.writeString(ARTIFACTS.resolve("export-summary.json"), json.writeValueAsString(planSummary.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("export-")).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))));
        planAssertions.assertAll();
    }

    private Result export(String keyword) {
        StockLogQuery query = new StockLogQuery(); query.setKeyword(keyword); query.setWarehouseId(warehouse);
        query.setCreatedDateFrom(LocalDate.of(2026, 1, 1)); query.setCreatedDateTo(LocalDate.of(2026, 1, 3));
        var rows = stockLogs.selectExportBatch(query, Keywords.codes(keyword), Keywords.codeContains(keyword), BASE + SIZE,
                OffsetDateTime.parse("2026-01-03T00:00:00Z"), Long.MAX_VALUE, 500);
        return new Result(rows.size(), rows.stream().map(row -> row.getId()).toList());
    }

    private Result seek(String keyword) {
        StockLogQuery q = new StockLogQuery(); q.setKeyword(keyword); q.setWarehouseId(warehouse);
        q.setCreatedDateFrom(LocalDate.of(2026, 1, 1)); q.setCreatedDateTo(LocalDate.of(2026, 1, 3));
        var rows = stockLogs.selectSeek(q, Keywords.codes(keyword), Keywords.codeContains(keyword), OffsetDateTime.parse("2026-01-03T00:00:00Z"), Long.MAX_VALUE, 101);
        return new Result(rows.size(), rows.stream().map(row -> row.getId()).toList());
    }
    private Result inventoryDocument(String keyword) {
        InvDocQuery q = new InvDocQuery(); q.setKind(InvDocKind.IN); q.setKeyword(keyword);
        return result(inventoryDocuments.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result inventoryCount(String keyword) {
        CountQuery q = new CountQuery(); q.setKeyword(keyword);
        return result(inventoryCounts.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword), DataScope.current(false)));
    }
    private Result inventoryStock(String keyword, boolean summary) {
        StockQuery q = new StockQuery(); q.setKeyword(keyword);
        if (!summary) return result(inventoryStocks.selectRowPage(new Page<>(1, 100), q, Keywords.codes(keyword)));
        var page = inventoryStocks.selectSummaryPage(new Page<>(1, 100), q, Keywords.codes(keyword));
        return new Result(page.getTotal(), page.getRecords().stream().map(row -> row.getMaterialSkuId()).toList());
    }
    private Result pickSku(String keyword) {
        MaterialPickQuery q = new MaterialPickQuery(); q.setKeyword(keyword);
        var page = materials.selectPickPage(new Page<>(1, 100), q, Keywords.codes(keyword));
        return new Result(page.getTotal(), page.getRecords().stream().map(row -> row.getSkuId()).toList());
    }
    private static Result result(IPage<?> page) {
        return new Result(page.getTotal(), page.getRecords().stream()
                .map(row -> (Long) PropertyAccessorFactory.forBeanPropertyAccess(row).getPropertyValue("id")).toList());
    }
    private record Result(long total, List<Long> ids) { }
    private long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private void bulk(String sql, Object... args) {
        String prepared = sql.replace("@base@", Long.toString(BASE));
        if (!prepared.contains("generate_series(1,100000)")) {
            jdbc.update(prepared, args);
            return;
        }
        var insert = BULK_INSERT_TABLE.matcher(prepared);
        if (!insert.find()) throw new IllegalArgumentException("Expected a static bulk INSERT fixture");
        String table = insert.group(1);
        if (!LARGE_TABLES.contains(table) && !"mrp_calc_order".equals(table)) {
            throw new IllegalArgumentException("Unexpected bulk fixture table");
        }
        // 大 fixture 同样遵守生产 30 秒超时，保留全部 FK；不以单条十万行写入依赖机器瞬时负载。
        for (int from = 1; from <= SIZE; from += 1000) {
            jdbc.update(prepared.replace("generate_series(1,100000)",
                    "generate_series(" + from + "," + Math.min(from + 999, SIZE) + ")"), args);
        }
        // 在加载子表前失效早期小表缓存的 FK 顺扫计划；保留全部数据、外键与最终统一 ANALYZE。
        jdbc.execute("ANALYZE " + table);
    }
    private static void login(long id, boolean all) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(id, "A1 query"), null,
                all ? List.of(new SimpleGrantedAuthority("data:all"), new SimpleGrantedAuthority("production:receipt:add")) : List.of()));
    }
    private static List<JsonNode> nodes(JsonNode root) {
        List<JsonNode> result = new ArrayList<>(); result.add(root);
        if (root.has("Plans")) root.get("Plans").forEach(child -> result.addAll(nodes(child)));
        return result;
    }
}
