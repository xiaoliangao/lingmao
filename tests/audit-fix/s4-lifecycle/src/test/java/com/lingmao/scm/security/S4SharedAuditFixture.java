package com.lingmao.scm.security;

import com.lingmao.scm.common.scope.DeploymentNamespace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 真实 SQL/HTTP 测试夹具；不依赖新增 guard 类，能原样叠到旧源码执行负控。 */
abstract class S4SharedAuditFixture extends A2BusinessFixture {
    @Autowired DeploymentNamespace namespace;
    private final List<Map<String,Object>> exchanges = new ArrayList<>();
    record Target(String table, long id) { }

    @AfterEach void saveSharedHttp(TestInfo info) throws Exception {
        evidence(info.getTestMethod().orElseThrow().getName() + "-" + info.getDisplayName().replaceAll("[^A-Za-z0-9_-]", "_") + ".http.json", exchanges);
    }

    @Override HttpResponse<byte[]> raw(Account actor, String method, String path, Object body, Long company, String key) throws Exception {
        var response = super.raw(actor, method, path, body, company, key);
        if (!path.startsWith("/api/auth/")) exchanges.add(map("method", method, "path", path,
                "companyHeader", company == null ? null : company.toString(), "httpStatus", response.statusCode(),
                "requestId", response.headers().firstValue("X-Request-Id").orElse(null), "response", node(response)));
        return response;
    }

    Target target(Fixture f, String table) {
        long id = switch (table) {
            case "bas_company" -> jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name,phone,address,credit_code) VALUES(?,'Shared audit company','S4','13800138000','synthetic address','SYNTHETIC-TAX') RETURNING id",
                    Long.class, suffix().substring(0,6).toUpperCase(java.util.Locale.ROOT));
            case "bas_warehouse" -> jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type,phone,address) VALUES(?,'Shared audit warehouse','MATERIAL','13800138000','synthetic address') RETURNING id",
                    Long.class, "S4AW" + suffix());
            case "bas_customer" -> jdbc.queryForObject("INSERT INTO bas_customer(code,name,short_name,contact,phone) VALUES(?,'Dormant customer fixture','S4','synthetic contact','13800138000') RETURNING id",
                    Long.class, "S4AC" + suffix());
            case "inv_stock" -> jdbc.queryForObject("INSERT INTO inv_stock(warehouse_id,material_sku_id,owner_type,owner_id,location_id,qty) VALUES(?,?,'OWN',0,0,12.125) RETURNING id",
                    Long.class, f.warehouse(), f.materialSku());
            case "prc_price" -> price(f, false).id();
            default -> throw new IllegalArgumentException("Unknown shared audit fixture table");
        };
        return new Target(table, id);
    }

    Target price(Fixture f, boolean process) {
        long id = process
                ? jdbc.queryForObject("INSERT INTO prc_price(type,supplier_id,style_id,process_mode,price,unit,tax_pct,effective_date) VALUES('PROCESS',?,?,'FULL',17.1234,'PIECE',13,CURRENT_DATE) RETURNING id",
                    Long.class, f.supplier(), f.style())
                : jdbc.queryForObject("INSERT INTO prc_price(type,supplier_id,material_id,price,unit,tax_pct,effective_date) VALUES('MATERIAL',?,?,23.4567,'M',13,CURRENT_DATE) RETURNING id",
                    Long.class, f.supplier(), f.material());
        return new Target("prc_price", id);
    }

    static String path(Target target, boolean root) {
        return "/api/data-logs?" + (root ? "rootTable=" : "table=") + target.table()
                + (root ? "&rootId=" : "&rowId=") + target.id() + "&limit=100";
    }

    JsonNode history(Account actor, Target target, boolean root, Long company) throws Exception {
        return ok(actor, "GET", path(target, root), null, company);
    }

    void evidence(String file, Object value) throws Exception {
        Path path = Path.of("test-artifacts/full-audit/shared-audit/s4", file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
