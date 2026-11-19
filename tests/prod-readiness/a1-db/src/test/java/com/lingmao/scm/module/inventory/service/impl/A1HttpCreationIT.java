package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.basic.dto.MaterialCreateReq;
import com.lingmao.scm.module.style.dto.StyleCreateReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.system.cache.DictCache;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 实际新增对象入口都经过 MVC/AOP/数据库；检查原结果重放，而不是只检查请求带了 UUID。 */
@IntegrationTest
@AutoConfigureMockMvc
class A1HttpCreationIT {
    private static final List<String> PERMISSIONS = List.of("basic:material:add", "basic:material:edit", "basic:material:list",
            "style:style:add", "style:style:edit", "style:style:list", "supplier:supplier:add", "supplier:supplier:list", "supplier:supplier:edit",
            "techpack:pack:add", "techpack:pack:edit", "techpack:bom:dev", "techpack:bom:prod", "techpack:pack:list", "purchase:order:add", "purchase:order:list",
            "production:node:edit", "production:issue:edit", "production:order:list", "techpack:cost:query");
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired Clock clock;
    @Autowired DictDataService dictionaries;
    @Autowired DictCache dictCache;
    @Autowired FileUrlSigner signer;
    private final List<Long> brands = new ArrayList<>();
    private long user;
    private Long currentCompany;
    private String suffix;

    @BeforeEach void login() {
        TestIdentity.clear();
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','A1 HTTP') RETURNING id", Long.class, "a1-http-" + suffix);
        TestIdentity.grant(jdbc, user, PERMISSIONS);
        SecurityContextHolder.getContext().setAuthentication(auth());
    }

    @AfterEach void cleanup() {
        for (long id : brands) jdbc.update("DELETE FROM sys_dict_data WHERE id=? AND type_code='brand'", id);
        if (!brands.isEmpty()) dictCache.evictAfterCommit("brand");
        TestIdentity.clear();
    }

    @Test void materialAutoNumberAndSkuCreationReplayTheOriginalIds() throws Exception {
        long material = createMaterial();
        var specs = Map.of("specs", List.of(Map.of("spec", "150cm", "specCode", "150")));
        JsonNode first = replayTwice("/api/materials/" + material + "/skus", specs);
        assertThat(first.get("data").isArray()).isTrue();
        assertThat(first.get("data").get(0).get("id").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material_sku WHERE material_id=? AND spec_code='150'", Integer.class, material)).isEqualTo(1);
    }

    @Test void supplierAutoNumberIsNotAllocatedAgainOnReplay() throws Exception {
        SupplierSaveReq req = new SupplierSaveReq();
        req.setName("A1 HTTP 供应商 " + suffix);
        req.setShortName("A1 " + suffix);
        req.setCategories(List.of("FABRIC"));
        JsonNode response = replayTwice("/api/suppliers", req);
        assertThat(response.get("data").get("id").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sup_supplier WHERE name=?", Integer.class, req.getName())).isEqualTo(1);
        long id = Long.parseLong(response.get("data").get("id").stringValue());
        JsonNode cert = replayTwice("/api/suppliers/" + id + "/certs", Map.of("certType", "OTHER",
                "fileUrl", signer.sign("/files/2026/11/17/a1-" + suffix + ".pdf"), "fileName", "A1证明.pdf"));
        assertThat(cert.get("data").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sup_supplier_cert WHERE supplier_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void styleAutoNumberAndIntegerSkuCountKeepTheirContracts() throws Exception {
        long style = createStyle();
        JsonNode generated = replayTwice("/api/styles/" + style + "/skus/generate", null);
        assertThat(generated.get("data").isIntegralNumber()).isTrue();
        assertThat(generated.get("data").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE name=?", Integer.class, "A1 HTTP 款 " + suffix)).isEqualTo(1);
    }

    @Test void techPackCreateAndReviseReplayOriginalScalarIds() throws Exception {
        long style = createStyle();
        JsonNode created = replayTwice("/api/tech-packs", Map.of("styleId", Long.toString(style)));
        assertThat(created.get("data").isString()).isTrue();
        long original = Long.parseLong(created.get("data").stringValue());
        // 生效版本是该生成接口的前置夹具；真正复制、取版号、落明细仍全部经 MVC/Service。
        jdbc.update("UPDATE tp_tech_pack SET status='EFFECTIVE' WHERE id=?", original);
        JsonNode revised = replayTwice("/api/tech-packs/" + original + "/revise", null);
        assertThat(revised.get("data").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tp_tech_pack WHERE style_id=?", Integer.class, style)).isEqualTo(2);
        int claims = jdbc.queryForObject("SELECT count(*) FROM biz_http_action WHERE user_id=?", Integer.class, user);
        JsonNode defaults = send("/api/tech-packs/" + original + "/bom-defaults", Map.of("items", List.of()), null, null);
        assertThat(defaults.get("code").intValue()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_http_action WHERE user_id=?", Integer.class, user)).isEqualTo(claims);
    }

    @Test void sourceMrpOwnershipIsRecheckedWithoutRequiringOriginalStateOrVersion() throws Exception {
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,?,'A1') RETURNING id", Long.class, suffix.substring(0, 6).toUpperCase(), "A1 HTTP " + suffix);
        currentCompany = company;
        jdbc.update("UPDATE sys_user SET default_company_id=? WHERE id=?", company, user);
        jdbc.update("INSERT INTO bas_warehouse(code,name,type) VALUES (?,?,'MATERIAL')", "A1H" + suffix, "A1 来源仓 " + suffix);
        long supplier = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,status,settle_method,tax_pct) VALUES (?,?,'A1','ACTIVE','MONTHLY',13) RETURNING id", Long.class, "A1" + suffix, "A1 来源 " + suffix);
        long material = createMaterial();
        jdbc.update("UPDATE bas_material SET default_supplier_id=?,ref_price=20 WHERE id=?", supplier, material);
        long sku = jdbc.queryForObject("SELECT id FROM bas_material_sku WHERE material_id=? LIMIT 1", Long.class, material);
        long calc = jdbc.queryForObject("INSERT INTO mrp_calc(no,company_id,status,create_by) VALUES (?,?,'CONFIRMED',?) RETURNING id", Long.class, "A1M" + suffix, company, user);
        long line = jdbc.queryForObject("""
                INSERT INTO mrp_calc_item(calc_id,seq,material_sku_id,supplier_id,gross_qty,need_qty,net_qty,
                    purchase_unit,conversion_factor,suggest_qty,ref_price,est_amount)
                VALUES (?,1,?,?,3,3,3,'METER',1,3,20,60) RETURNING id
                """, Long.class, calc, sku, supplier);
        var body = Map.of("calcId", Long.toString(calc), "itemIds", List.of(Long.toString(line)), "version", 0);
        String key = UUID.randomUUID().toString();
        JsonNode created = send("/api/purchase-orders/from-mrp", body, key, null);
        assertThat(created.get("code").intValue()).as(created.toString()).isZero();
        jdbc.update("UPDATE mrp_calc SET status='CLOSED' WHERE id=?", calc);
        assertThat(send("/api/purchase-orders/from-mrp", body, key, null)).isEqualTo(created);
        assertThat(send("/api/purchase-orders/from-mrp", body, key, Long.toString(company)).get("code").intValue()).isZero();
        assertThat(send("/api/purchase-orders/from-mrp", body, key, Long.toString(company + 1)).get("code").intValue()).isEqualTo(1002);
        jdbc.update("UPDATE mrp_calc SET create_by=NULL WHERE id=?", calc);
        assertThat(send("/api/purchase-orders/from-mrp", body, key, null).get("code").intValue()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pur_order WHERE calc_id=?", Integer.class, calc)).isEqualTo(1);
    }

    @Test void productionReportAndIssueReplayWithoutAddingAnotherEvent() throws Exception {
        long style = createStyle();
        long sku = jdbc.queryForObject("SELECT id FROM sty_style_sku WHERE style_id=? LIMIT 1", Long.class, style);
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,?,'A1') RETURNING id", Long.class, suffix.substring(0,6).toUpperCase(), "A1 生产 " + suffix);
        currentCompany = company;
        long factory = jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name,status) VALUES (?,?,'A1','ACTIVE') RETURNING id", Long.class, "A1F" + suffix, "A1 工厂 " + suffix);
        long pack = jdbc.queryForObject("INSERT INTO tp_tech_pack(style_id,stage,version,status) VALUES (?,'PROD',1,'EFFECTIVE') RETURNING id", Long.class, style);
        long order = jdbc.queryForObject("""
                INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,commit_date,sales_id,merch_id,status,total_qty,create_by)
                VALUES (?,?,'OWN',CURRENT_DATE,CURRENT_DATE+10,CURRENT_DATE+10,?,?,'RUNNING',2,?) RETURNING id
                """, Long.class, "A1O" + suffix, company, user, user, user);
        long orderLine = jdbc.queryForObject("""
                INSERT INTO ord_order_item(order_id,seq,style_id,style_sku_id,qty,price,amount,tech_pack_id,scheduled_qty)
                VALUES (?,1,?,?,2,10,20,?,2) RETURNING id
                """, Long.class, order, style, sku, pack);
        long production = jdbc.queryForObject("""
                INSERT INTO prd_order(no,company_id,kind,factory_id,process_mode,order_id,style_id,tech_pack_id,
                    order_date,due_date,unit_price,tax_pct,total_qty,total_amount,merch_id,settle_method,status,create_by)
                VALUES (?,?,'OUTSOURCE',?,'CMT',?,?,?,CURRENT_DATE,CURRENT_DATE+10,10,13,2,20,?,'MONTHLY','APPROVED',?) RETURNING id
                """, Long.class, "A1P" + suffix, company, factory, order, style, pack, user, user);
        jdbc.update("INSERT INTO prd_order_item(prd_order_id,seq,order_item_id,style_sku_id,qty) VALUES (?,1,?,?,2)", production, orderLine, sku);
        long node = jdbc.queryForObject("""
                INSERT INTO prd_node(prd_order_id,seq,node_code,node_name,plan_start,plan_finish)
                VALUES (?,1,'CUT','裁剪',CURRENT_DATE,CURRENT_DATE+1) RETURNING id
                """, Long.class, production);
        String reportPath = "/api/production-orders/" + production + "/nodes/" + node + "/reports";
        JsonNode report = replayTwice(reportPath, Map.of("action", "START", "actualStart", LocalDate.now(clock).toString(), "doneQty", 1));
        assertThat(report.get("data").get("status").stringValue()).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prd_node_report WHERE prd_order_id=?", Integer.class, production)).isEqualTo(1);
        var issueBody = Map.of("issueType", "OTHER", "description", "A1 同键异常",
                "photos", List.of(Map.of("url", signer.sign("/files/2026/11/17/a1-" + suffix + ".jpg"), "name", "现场.jpg", "size", 1024)));
        String issuePath = "/api/production-orders/" + production + "/issues";
        String issueKey = UUID.randomUUID().toString();
        JsonNode issue = send(issuePath, issueBody, issueKey, null);
        assertThat(issue.get("code").intValue()).as(issue.toString()).isZero();
        assertThat(send(issuePath, issueBody, issueKey, null)).isEqualTo(issue);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM prd_issue WHERE prd_order_id=?", Integer.class, production)).isEqualTo(1);
        long other = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','其他跟单') RETURNING id", Long.class, "a1-other-" + suffix);
        jdbc.update("UPDATE prd_order SET merch_id=?,create_by=? WHERE id=?", other, other, production);
        assertThat(send(issuePath, issueBody, issueKey, null).get("code").intValue()).isEqualTo(404);
    }

    private long createMaterial() throws Exception {
        long parent = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", Long.class, "A1 HTTP 一级 " + suffix);
        String code = jdbc.queryForObject("""
                SELECT chr(a)||chr(b) FROM generate_series(65,90) a CROSS JOIN generate_series(65,90) b
                WHERE NOT EXISTS (SELECT 1 FROM bas_material_category c WHERE c.code=chr(a)||chr(b)) LIMIT 1
                """, String.class);
        long category = jdbc.queryForObject("INSERT INTO bas_material_category(parent_id,code,name,level) VALUES (?,?,?,2) RETURNING id", Long.class, parent, code, "A1 HTTP 二级 " + suffix);
        MaterialCreateReq req = new MaterialCreateReq();
        req.setCategoryId(category);
        req.setName("A1 HTTP 物料 " + suffix);
        req.setMaterialType("FABRIC");
        req.setStockUnit("METER");
        JsonNode created = replayTwice("/api/materials", req);
        assertThat(created.get("data").isString()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material WHERE name=?", Integer.class, req.getName())).isEqualTo(1);
        return Long.parseLong(created.get("data").stringValue());
    }

    private long createStyle() throws Exception {
        String designer = jdbc.queryForObject("""
                SELECT lpad(n::text,2,'0') FROM generate_series(1,99) n
                WHERE NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.designer_no=lpad(n::text,2,'0')) LIMIT 1
                """, String.class);
        jdbc.update("UPDATE sys_user SET designer_no=? WHERE id=?", designer, user);
        long category = jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES (?,1) RETURNING id", Long.class, "A1 款类 " + suffix);
        long group = jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES (?,'M') RETURNING id", Long.class, "A1 尺码 " + suffix);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES (?,1,'M')", group);
        var brand = new DictDataSaveReq();
        brand.setTypeCode("brand");
        brand.setLabel("A1 品牌 " + suffix);
        brand.setValue("A1" + suffix.toUpperCase());
        brands.add(dictionaries.create(brand));
        long color = TestColors.insert(jdbc, "A1 色 " + suffix);
        var req = new StyleCreateReq();
        req.setCodeMode(StyleCreateReq.CodeMode.AUTO);
        req.setName("A1 HTTP 款 " + suffix);
        req.setStyleClass("FS");
        req.setBrand(brand.getValue());
        req.setCategoryId(category);
        req.setYear(LocalDate.now(clock).getYear());
        req.setSeason("SPRING");
        req.setSizeGroupId(group);
        req.setDesignerId(user);
        req.setColorIds(List.of(color));
        JsonNode response = replayTwice("/api/styles", req);
        assertThat(response.get("data").get("skuCount").isIntegralNumber()).isTrue();
        return Long.parseLong(response.get("data").get("id").stringValue());
    }

    private JsonNode replayTwice(String path, Object body) throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = send(path, body, key, null);
        assertThat(first.get("code").intValue()).as(path + ": " + first).isZero();
        assertThat(send(path, body, key, null)).as(path + " 原结果重放").isEqualTo(first);
        return first;
    }

    private JsonNode send(String path, Object body, String key, String company) throws Exception {
        var request = post(path).with(authentication(auth()));
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (key != null) request.header("Idempotency-Key", key);
        if (company != null || currentCompany != null) request.header("X-Company-Id", company == null ? currentCompany.toString() : company);
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(new LoginUser(user, "a1-http"), null,
                PERMISSIONS.stream().map(SimpleGrantedAuthority::new).toList());
    }
}
