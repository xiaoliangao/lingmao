package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.service.SupplierExcelService;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Supplier export uses actual relation counts, SQL limits and real HTTP workbook/error responses. */
class S2SupplierExportBoundsHttpIT extends A2BusinessFixture {
    @Autowired SupplierExcelService exports;

    @Test void categoryRelationsConsumeTheSqlBudgetAndDoNotReadTheThirdParentBatch() throws Exception {
        overflow(false);
    }

    @Test void goodAtRelationsConsumeTheirOwnSqlBudgetAndDoNotReadTheThirdParentBatch() throws Exception {
        overflow(true);
    }

    private void overflow(boolean goodAt) throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "supplier:supplier:export");
        String prefix = "P37S" + suffix();
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO sup_supplier(code,name,short_name,status)
                SELECT ? || lpad(n::text,4,'0'),'P37 synthetic supplier','P37 synthetic','ACTIVE'
                FROM generate_series(1,2001) n RETURNING id
                """, Long.class, prefix);
        List<Long> categories = List.of();
        String table = goodAt ? "sup_supplier_good_at" : "sup_supplier_category";
        String name = goodAt ? "good-at-relations" : "category-relations";
        try {
            if (goodAt) {
                categories = jdbc.queryForList("""
                        INSERT INTO bas_category(name,level,sort)
                        SELECT ? || n,1,n FROM generate_series(1,50) n RETURNING id
                        """, Long.class, prefix);
                assertThat(jdbc.update("""
                        INSERT INTO sup_supplier_good_at(supplier_id,category_id)
                        SELECT s.id,c.id FROM sup_supplier s CROSS JOIN bas_category c
                        WHERE s.code LIKE ? AND c.name LIKE ?
                          AND c.sort <= CASE WHEN right(s.code,4)::int < 1000 THEN 50
                                             WHEN right(s.code,4)::int = 1000 THEN 49 ELSE 1 END
                        """, prefix + "%", prefix + "%")).isEqualTo(51_000);
            } else {
                assertThat(jdbc.update("""
                        INSERT INTO sup_supplier_category(supplier_id,category)
                        SELECT s.id,? || n FROM sup_supplier s CROSS JOIN generate_series(1,50) n
                        WHERE s.code LIKE ?
                          AND n <= CASE WHEN right(s.code,4)::int < 1000 THEN 50
                                        WHEN right(s.code,4)::int = 1000 THEN 49 ELSE 1 END
                        """, prefix, prefix + "%")).isEqualTo(51_000);
            }
            assertThat(count(table, "supplier_id", ids)).isEqualTo(51_000L);
            authenticate(reader);
            SupplierQuery query = new SupplierQuery(); query.setKeyword(prefix);
            var capture = attempt(() -> exports.export(query));
            sqlEvidence(name, capture, 2001, 51_000);
            List<BoundSql> relationQueries = capture.statements().stream()
                    .filter(s -> normalized(s.getSql()).contains("from " + table + " ")).toList();
            assertThat(relationQueries).isNotEmpty();
            assertThat(relationQueries).allSatisfy(s -> assertThat(normalized(s.getSql())).startsWith("select count(*) from (select 1").contains("limit ?) bounded"));
            assertThat(relationQueries.stream().map(S2SupplierExportBoundsHttpIT::limitValue).toList())
                    .containsExactly(50_001L, 25_001L, 2L);
            assertThat(capture.result().code()).isEqualTo(1000);
            if (!goodAt) assertThat(capture.statements()).noneMatch(s -> normalized(s.getSql()).contains("from sup_supplier_good_at "));
            var response = raw(reader, "GET", "/api/suppliers/export?keyword=" + prefix, null, null, null);
            write(name + "-http.json", map("status", response.statusCode(), "response", node(response),
                    "downloadHeaderPresent", response.headers().firstValue("Content-Disposition").isPresent()));
            assertThat(node(response).path("code").intValue()).isEqualTo(1000);
            assertThat(node(response).path("msg").stringValue()).contains(goodAt ? "供应商擅长品类关联" : "供应商分类关联", "50000");
            assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        } finally {
            delete(table, "supplier_id", ids);
            delete("sup_supplier", "id", ids);
            delete("bas_category", "id", categories);
        }
    }

    @Test void anOversizedAggregatedCellReturnsAControlledSheetRowColumnError() throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "supplier:supplier:export");
        String prefix = "P37L" + suffix();
        List<Long> categories = jdbc.queryForList("""
                INSERT INTO bas_category(name,level,sort)
                SELECT rpad(? || lpad(n::text,4,'0'),50,'x'),1,n FROM generate_series(1,700) n RETURNING id
                """, Long.class, prefix);
        try {
            jdbc.update("INSERT INTO sup_supplier_good_at(supplier_id,category_id) SELECT ?,id FROM bas_category WHERE name LIKE ?",
                    f.supplier(), prefix + "%");
            long characters = jdbc.queryForObject("""
                    SELECT sum(length(c.name)) + count(*) - 1 FROM sup_supplier_good_at g
                    JOIN bas_category c ON c.id=g.category_id WHERE g.supplier_id=?
                    """, Long.class, f.supplier());
            assertThat(characters).isEqualTo(35_699L);
            var response = raw(reader, "GET", "/api/suppliers/export?ids=" + f.supplier(), null, null, null);
            write("long-cell-http.json", map("database", database(), "relatedRows", 700, "cellCharacters", characters,
                    "status", response.statusCode(), "response", node(response),
                    "downloadHeaderPresent", response.headers().firstValue("Content-Disposition").isPresent()));
            assertThat(node(response).path("code").intValue()).isEqualTo(1000);
            assertThat(node(response).path("msg").stringValue())
                    .contains("工作表「供应商管理」第 2 行第 6 列（擅长类目）", "32767").doesNotContain(prefix);
            assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        } finally {
            jdbc.update("DELETE FROM sup_supplier_good_at WHERE supplier_id=?", f.supplier());
            delete("bas_category", "id", categories);
        }
    }

    @Test void legitimateRelationsKeepDictionaryAndTreeOrderUnknownValuesAndFieldPermissions() throws Exception {
        Fixture f = fixture();
        Account reader = custom(f.company(), "ALL", "supplier:supplier:export");
        Account admin = role("admin", f.company());
        String prefix = "P37N" + suffix(), unknown = prefix + "_OLD";
        jdbc.update("INSERT INTO sup_supplier_category(supplier_id,category) VALUES(?,?)", f.supplier(), unknown);
        List<Long> categories = jdbc.queryForList("""
                INSERT INTO bas_category(name,level,sort)
                VALUES(?,1,20),(?,1,10) RETURNING id
                """, Long.class, prefix + "_last", prefix + "_first");
        try {
            jdbc.update("INSERT INTO sup_supplier_good_at(supplier_id,category_id) VALUES(?,?),(?,?)",
                    f.supplier(), categories.getFirst(), f.supplier(), categories.getLast());
            jdbc.update("UPDATE sup_supplier SET name=?,remark=? WHERE id=?", "=P37 synthetic", "@P37 synthetic", f.supplier());
            List<String> labels = jdbc.queryForList("""
                    SELECT label FROM sys_dict_data WHERE type_code='supplier_category' AND value IN ('FABRIC','GARMENT')
                    AND NOT deleted ORDER BY sort,id
                    """, String.class);
            String expectedCategories = String.join("、", labels) + "、" + unknown;
            List<Object> evidence = new ArrayList<>();
            for (Account actor : List.of(reader, admin)) {
                var response = raw(actor, "GET", "/api/suppliers/export?ids=" + f.supplier(), null, null, null);
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("filename*=UTF-8''");
                String name = actor.id() == admin.id() ? "normal-with-contact.xlsx" : "normal-without-contact.xlsx";
                Files.createDirectories(evidenceDir()); Files.write(evidenceDir().resolve(name), response.body());
                try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(response.body()))) {
                    assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
                    Sheet sheet = workbook.getSheetAt(0);
                    List<String> headers = headers(sheet);
                    assertThat(sheet.getLastRowNum()).isEqualTo(1);
                    assertThat(text(sheet, headers, "分类")).isEqualTo(expectedCategories);
                    assertThat(text(sheet, headers, "擅长类目")).isEqualTo(prefix + "_first、" + prefix + "_last");
                    assertThat(text(sheet, headers, "全称")).isEqualTo("'=P37 synthetic");
                    assertThat(text(sheet, headers, "备注")).isEqualTo("'@P37 synthetic");
                    assertThat(headers).doesNotContain("账户", "开户行", "付款账户名");
                    if (actor.id() == admin.id()) assertThat(headers).contains("联系人", "联系电话", "公司地址");
                    else assertThat(headers).doesNotContain("联系人", "联系电话", "公司地址", "微信", "地址电话", "传真");
                    for (var row : sheet) for (var cell : row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                    evidence.add(map("file", name, "headers", headers, "rows", 1, "hasContactPermission", actor.id() == admin.id()));
                }
            }
            write("normal-workbooks.json", evidence);
        } finally {
            jdbc.update("DELETE FROM sup_supplier_good_at WHERE supplier_id=?", f.supplier());
            delete("bas_category", "id", categories);
            jdbc.update("DELETE FROM sup_supplier_category WHERE supplier_id=? AND category=?", f.supplier(), unknown);
        }
    }

    @Test void categoryCodeLengthMatchesTheExistingDatabaseFieldCapacity() throws Exception {
        Fixture f = fixture();
        Account creator = custom(f.company(), "ALL", "supplier:supplier:add");
        String name = "P37 invalid " + suffix();
        var response = call(creator, "POST", "/api/suppliers", map("name", name, "shortName", "P37 invalid",
                "categories", List.of("X".repeat(65))), f.company());
        write("category-code-validation.json", map("response", response));
        assertThat(response.path("code").intValue()).isEqualTo(400);
        assertThat(response.path("msg").stringValue()).contains("分类编码最多 64 个字");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sup_supplier WHERE name=?", Long.class, name)).isZero();
    }

    private record Attempt(int code, String message, int bytes) { }
    private static SqlStatementCapture.Captured<Attempt> attempt(Supplier<byte[]> operation) {
        return SqlStatementCapture.capture(() -> {
            try { return new Attempt(0, "success", operation.get().length); }
            catch (BizException error) { return new Attempt(error.getCode(), error.getMessage(), 0); }
        });
    }
    private static String normalized(String sql) { return sql.replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ",").strip().toLowerCase(Locale.ROOT); }
    private static Long limitValue(BoundSql bound) { return ExportSqlLimits.limit(bound); }
    private void sqlEvidence(String name, SqlStatementCapture.Captured<Attempt> capture, int parents, int children) throws Exception {
        write(name + "-sql.json", map("database", database(), "parentRows", parents, "relatedRows", children,
                "limit", 50_000, "attempt", capture.result(), "statements", capture.statements().stream()
                        .map(s -> map("sql", normalized(s.getSql()), "boundLimit", limitValue(s))).toList(),
                "scope", "Synthetic real database capacity and SQL stop controls, not concurrent-insert/OOM/cancellation evidence."));
    }
    private String database() { return jdbc.queryForObject("SELECT current_database()", String.class); }
    private long count(String table, String column, List<Long> ids) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " IN (" + marks(ids) + ")", Long.class, ids.toArray());
    }
    private void delete(String table, String column, List<Long> ids) {
        if (!ids.isEmpty()) jdbc.update("DELETE FROM " + table + " WHERE " + column + " IN (" + marks(ids) + ")", ids.toArray());
    }
    private static String marks(List<Long> ids) { return String.join(",", Collections.nCopies(ids.size(), "?")); }
    private static List<String> headers(Sheet sheet) {
        List<String> out = new ArrayList<>(); sheet.getRow(0).forEach(cell -> out.add(cell.getStringCellValue())); return out;
    }
    private static String text(Sheet sheet, List<String> headers, String column) {
        int index = headers.indexOf(column); assertThat(index).isNotNegative(); return sheet.getRow(1).getCell(index).getStringCellValue();
    }
    private static Path evidenceDir() { return Path.of("test-artifacts/full-audit/s2/p37-supplier"); }
    private void write(String name, Object value) throws Exception {
        Files.createDirectories(evidenceDir()); Files.writeString(evidenceDir().resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
}
