package com.lingmao.scm.security;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.TestColors;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "lingmao.jobs.enabled=false")
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
abstract class A2BusinessFixture {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired com.lingmao.scm.module.system.service.PermissionService permissions;
    @Autowired Clock clock;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    record Account(long id, long role, String token) { }
    record Fixture(long company, long otherCompany, long warehouse, long factoryWarehouse, long supplier,
                   long material, long materialSku, String materialCode, String materialSkuCode,
                   long materialCategory, String materialCategoryCode, String materialPath,
                   long style, String styleCode, long styleSku, long color, String colorCode,
                   long pack, long bom, long category, String categoryName, long sizeGroup, String sizeGroupName) { }
    @AfterEach void closeClient() { http.close(); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12); }
    LocalDate today() { return LocalDate.now(clock); }

    Account role(String code, long company) throws Exception {
        long role = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=? AND NOT deleted", Long.class, code);
        return account(role, company);
    }
    Account custom(long company, String scope, String... permissions) throws Exception {
        String name = "a2_" + suffix();
        long role = jdbc.queryForObject("INSERT INTO sys_role(code,name,data_scope) VALUES(?,?,?) RETURNING id", Long.class, name, name, scope);
        for (String permission : permissions) {
            assertThat(jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT ?,id FROM sys_menu WHERE perms=? AND NOT deleted", role, permission))
                    .as("fixture permission %s exists", permission).isPositive();
        }
        return account(role, company);
    }
    private Account account(long role, long company) throws Exception {
        String name = "a2_" + suffix(); String secret = "A2-Business-123456";
        long user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname,default_company_id) VALUES(?,?,?,?) RETURNING id",
                Long.class, name, passwords.encode(secret), name, company);
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user, role);
        JsonNode reply = node(raw(null, "POST", "/api/auth/login", Map.of("username", name, "password", secret), null, null));
        assertThat(reply.path("code").intValue()).isZero();
        return new Account(user, role, reply.path("data").stringValue());
    }
    void authenticate(Account account) {
        var authorities = permissions.loadPermissions(account.id());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new LoginUser(account.id(), "direct-it"), null,
                        authorities.stream().map(org.springframework.security.core.authority.SimpleGrantedAuthority::new).toList()));
    }
    HttpResponse<byte[]> raw(Account actor, String method, String path, Object body, Long company, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
        if (actor != null) request.header("Authorization", "Bearer " + actor.token());
        if (company != null) request.header("X-Company-Id", company.toString());
        if (key != null) request.header("Idempotency-Key", key);
        request.header("Content-Type", "application/json");
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonNode node(HttpResponse<byte[]> response) { return json.readTree(response.body()); }
    JsonNode call(Account actor, String method, String path, Object body, Long company) throws Exception {
        return node(raw(actor, method, path, body, company, method.equals("POST") ? UUID.randomUUID().toString() : null));
    }
    JsonNode ok(Account actor, String method, String path, Object body, Long company) throws Exception {
        var response = call(actor, method, path, body, company);
        assertThat(response.path("code").intValue()).as("%s %s: %s", method, path, response.path("msg")).isZero();
        return response.path("data");
    }
    static long id(JsonNode node) { return node.isString() ? Long.parseLong(node.stringValue()) : node.longValue(); }
    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    Map<String, Object> orderBody(Fixture f, Account sales, Account merch, Object price) {
        return map("companyId", f.company(), "orderType", "STOCK", "orderDate", today(), "requiredDate", today().plusDays(30),
                "salesId", sales.id(), "merchId", merch.id(), "currency", "CNY",
                "items", List.of(map("styleSkuId", f.styleSku(), "qty", 10, "price", price)));
    }

    Fixture fixture() {
        String s = suffix();
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,'A2') RETURNING id", Long.class, s.substring(0, 6).toUpperCase(), "A2 company " + s);
        long other = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES(?,?,'A2B') RETURNING id", Long.class, s.substring(6).toUpperCase(), "A2 other " + s);
        long supplier = jdbc.queryForObject("""
                INSERT INTO sup_supplier(code,name,short_name,status,settle_method,tax_pct,contact,phone,wechat,address,bank_name,bank_account_name,bank_account,legacy_attrs)
                VALUES(?,?,'A2 factory','ACTIVE','MONTHLY',13,'private-contact','13812345678','private-wechat','private-address',
                    'private-bank','private-bank-holder','6228480000000012345','{"地址电话":"private-invoice-phone","传真":"private-fax"}') RETURNING id
                """, Long.class, "A2S" + s, "A2 supplier " + s);
        jdbc.update("INSERT INTO sup_supplier_category(supplier_id,category) VALUES(?,'GARMENT'),(?,'FABRIC')", supplier, supplier);
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES(?,?,'MATERIAL') RETURNING id", Long.class, "A2W" + s, "A2 warehouse " + s);
        long factoryWarehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type,factory_id) VALUES(?,?,'FACTORY',?) RETURNING id", Long.class, "A2F" + s, "A2 factory warehouse " + s, supplier);
        String parentName = "A2 parent " + s, childName = "A2 child " + s;
        long parent = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES(?,1) RETURNING id", Long.class, parentName);
        String categoryCode = jdbc.queryForObject("""
                SELECT chr(a)||chr(b) FROM generate_series(65,90) a CROSS JOIN generate_series(65,90) b
                WHERE NOT EXISTS(SELECT 1 FROM bas_material_category WHERE code=chr(a)||chr(b)) LIMIT 1
                """, String.class);
        long materialCategory = jdbc.queryForObject("INSERT INTO bas_material_category(parent_id,code,name,level) VALUES(?,?,?,2) RETURNING id", Long.class, parent, categoryCode, childName);
        String materialCode = categoryCode + "0001", skuCode = materialCode + "-A";
        long color = TestColors.insert(jdbc, "A2 color " + s);
        String colorCode = jdbc.queryForObject("SELECT code FROM bas_color WHERE id=?", String.class, color);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit,default_supplier_id,ref_price,legacy_attrs)
                VALUES(?, 'A2 material',?,'FABRIC','METER','METER',?,12.34,'{"其他价格":"765.43","倍率":"2.7"}') RETURNING id
                """, Long.class, materialCode, materialCategory, supplier);
        long materialSku = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code,color_id,price) VALUES(?,?,?,13.37) RETURNING id", Long.class, material, skuCode, color);
        String categoryName = "A2 style category " + s, sizeGroupName = "A2 sizes " + s;
        long category = jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES(?,1) RETURNING id", Long.class, categoryName);
        long sizes = jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES(?,'M') RETURNING id", Long.class, sizeGroupName);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,1,'M')", sizes);
        String styleCode = "A2ST" + s.toUpperCase();
        long style = jdbc.queryForObject("""
                INSERT INTO sty_style(code,name,brand,style_class,category_id,year,season,source,size_group_id,tag_price,ref_cost,status,default_factory_id,legacy_attrs)
                VALUES(?,'A2 style','A2','FS',?,2026,'AUTUMN','OWN',?,299,456.78,'ENABLED',?,'{"财务成本价":"987.65"}') RETURNING id
                """, Long.class, styleCode, category, sizes, supplier);
        jdbc.update("INSERT INTO sty_style_color(style_id,color_id,seq) VALUES(?,?,1)", style, color);
        long styleSku = jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,'M') RETURNING id", Long.class, style, styleCode + "-" + colorCode + "-M", color);
        long pack = jdbc.queryForObject("INSERT INTO tp_tech_pack(style_id,stage,version,status) VALUES(?,'PROD',1,'EFFECTIVE') RETURNING id", Long.class, style);
        long bom = jdbc.queryForObject("""
                INSERT INTO tp_bom_item(tech_pack_id,seq,part,material_id,sku_mode,usage_qty,unit,supplier_id,ref_price)
                VALUES(?,1,'body',?,'BY_COLOR',1.5,'METER',?,777.77) RETURNING id
                """, Long.class, pack, material, supplier);
        jdbc.update("INSERT INTO tp_bom_item_sku(bom_item_id,color_id,material_sku_id) VALUES(?,?,?)", bom, color, materialSku);
        return new Fixture(company, other, warehouse, factoryWarehouse, supplier, material, materialSku, materialCode, skuCode,
                materialCategory, categoryCode, parentName + " > " + childName, style, styleCode, styleSku, color, colorCode, pack, bom, category, categoryName, sizes, sizeGroupName);
    }
    long sourceOrder(Fixture f, Account owner, String state, long company) {
        long order = jdbc.queryForObject("""
                INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,commit_date,sales_id,merch_id,status,total_qty,total_amount,create_by)
                VALUES(?,?,'STOCK',CURRENT_DATE,CURRENT_DATE+30,CURRENT_DATE+30,?,?,?,10,890,?) RETURNING id
                """, Long.class, "A2O" + suffix(), company, owner.id(), owner.id(), state, owner.id());
        jdbc.update("INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount,tech_pack_id) VALUES(?,1,?,?,10,89,890,?)", order, f.style(), f.styleSku(), f.pack());
        return order;
    }
    long sourcePurchase(Fixture f, Account owner) {
        long purchase = jdbc.queryForObject("""
                INSERT INTO pur_order(no,company_id,supplier_id,purchase_type,buyer_id,order_date,delivery_date,warehouse_id,settle_method,tax_pct,status,total_qty,total_amount,create_by)
                VALUES(?,?,?,'STOCK',?,CURRENT_DATE,CURRENT_DATE+30,?,'MONTHLY',13,'APPROVED',10,133.7,?) RETURNING id
                """, Long.class, "A2P" + suffix(), f.company(), f.supplier(), owner.id(), f.warehouse(), owner.id());
        jdbc.update("""
                INSERT INTO pur_order_item(purchase_order_id,seq,material_sku_id,purchase_unit,conversion_factor,qty,price,amount)
                VALUES(?,1,?,'METER',1,10,13.37,133.7)
                """, purchase, f.materialSku());
        return purchase;
    }
    long sourceProduction(Fixture f, Account owner, long order, String state) {
        long orderItem = jdbc.queryForObject("SELECT id FROM ord_order_item WHERE order_id=?", Long.class, order);
        long production = jdbc.queryForObject("""
                INSERT INTO prd_order(no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,order_date,due_date,unit_price,tax_pct,total_qty,total_amount,merch_id,settle_method,status,issue_status,create_by)
                VALUES(?,?,'OUTSOURCE',?,'CMT',?,?,?,CURRENT_DATE,CURRENT_DATE+30,38.31,13,2,76.62,?,'MONTHLY',?,'NONE',?) RETURNING id
                """, Long.class, "A2R" + suffix(), f.company(), f.supplier(), order, f.style(), f.pack(), owner.id(), state, owner.id());
        jdbc.update("INSERT INTO prd_order_item(prd_order_id,seq,order_item_id,style_sku_id,qty) VALUES(?,1,?,?,2)", production, orderItem, f.styleSku());
        jdbc.update("UPDATE ord_order_item SET scheduled_qty=2 WHERE id=?", orderItem);
        return production;
    }
}
