package com.lingmao.scm.security;

import com.lingmao.scm.SqlStatementCapture;
import com.lingmao.scm.TestColors;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.basic.dto.MaterialQuery;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.style.dto.StyleQuery;
import com.lingmao.scm.module.style.service.StyleService;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntBinaryOperator;
import java.util.function.IntSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;

/** P37: actual 50,000-row production limit, real HTTP exports and PostgreSQL prepared SQL. */
class S2MasterExportBoundsHttpIT extends A2BusinessFixture {
    @Autowired MaterialService materials;
    @Autowired StyleService styles;

    @Test void twoMaterialsWithFiftyThousandAndOneSkusAreRejectedByABoundedQuery() throws Exception {
        Fixture f = fixture();
        Account exporter = custom(f.company(), "ALL", "basic:material:export");
        String prefix = "P37M" + suffix();
        long second = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
                VALUES(?,'P37 synthetic material',?,'FABRIC','METER','METER') RETURNING id
                """, Long.class, prefix, f.materialCategory());
        try {
            seedInBatches("p37-material-sku-fixture", 25_000, 2, (from, to) -> jdbc.update("""
                    INSERT INTO bas_material_sku(material_id,code,color_id,spec_code)
                    SELECT parents.id, ? || '-' || parents.id || '-' || n, ?, 'P37-' || n
                    FROM (VALUES (CAST(? AS bigint)),(CAST(? AS bigint))) parents(id)
                    CROSS JOIN generate_series(?,?) n
                    """, prefix, f.color(), f.material(), second, from, to));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material_sku WHERE material_id IN (?,?)", Long.class,
                    f.material(), second)).isEqualTo(50_001L);
            authenticate(exporter);
            MaterialQuery query = new MaterialQuery(); query.setIds(List.of(f.material(), second));
            var captured = rejectedSql(() -> materials.export(query));
            sqlEvidence("p37-material-overflow", 2, 50_001, captured);
            assertBounded(captured, "bas_material_sku", "order by material_id asc,id asc");
            assertThat(queries(captured, "bas_color")).isEmpty();
            HttpResponse<byte[]> reply = rejectedExport(exporter, "/api/materials/export?ids=" + f.material() + "," + second, "物料信息SKU");
            evidence("p37-material-overflow", 2, 50_001, reply, captured);
            exactMaterialLimit(f, exporter, second, prefix);
        } finally {
            cleanupInBatches("p37-material-sku-cleanup", () -> jdbc.update("""
                    DELETE FROM bas_material_sku WHERE id IN (
                      SELECT id FROM bas_material_sku WHERE material_id IN (?,?) AND code LIKE ? ORDER BY id LIMIT 5000)
                    """, f.material(), second, prefix + "-%"));
            jdbc.update("DELETE FROM bas_material WHERE id=?", second);
        }
    }

    @Test void aSingleStyleWithFiftyThousandAndOneSkusIsRejectedByABoundedQuery() throws Exception {
        Fixture f = fixture();
        Account exporter = custom(f.company(), "ALL", "style:style:export");
        String prefix = "P37S" + suffix();
        try {
            seedInBatches("p37-style-sku-fixture", 50_000, 1, (from, to) -> jdbc.update("""
                    INSERT INTO sty_style_sku(style_id,code,color_id,size_code)
                    SELECT ?, ? || '-' || n, ?, 'P37-' || n FROM generate_series(?,?) n
                    """, f.style(), prefix, f.color(), from, to));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style_sku WHERE style_id=?", Long.class, f.style())).isEqualTo(50_001L);
            authenticate(exporter);
            StyleQuery query = new StyleQuery(); query.setIds(List.of(f.style()));
            var captured = rejectedSql(() -> styles.export(query));
            sqlEvidence("p37-style-sku-overflow", 1, 50_001, captured);
            assertBounded(captured, "sty_style_color", "order by style_id asc,seq asc,id asc");
            assertBounded(captured, "sty_style_sku", "order by style_id asc,id asc");
            assertThat(queries(captured, "bas_color")).isEmpty();
            HttpResponse<byte[]> reply = rejectedExport(exporter, "/api/styles/export?ids=" + f.style(), "导出 SKU");
            evidence("p37-style-sku-overflow", 1, 50_001, reply, captured);
        } finally {
            cleanupInBatches("p37-style-sku-cleanup", () -> jdbc.update("""
                    DELETE FROM sty_style_sku WHERE id IN (
                      SELECT id FROM sty_style_sku WHERE style_id=? AND code LIKE ? ORDER BY id LIMIT 5000)
                    """, f.style(), prefix + "-%"));
        }
    }

    @Test void tooManyStyleColorsStopBeforeTheSkuQueryOrWorkbookIsCreated() throws Exception {
        Fixture f = fixture();
        Account exporter = custom(f.company(), "ALL", "style:style:export");
        String prefix = "P37C" + suffix();
        long second = jdbc.queryForObject("""
                INSERT INTO sty_style(code,name,brand,style_class,category_id,year,season,source,size_group_id,status)
                VALUES(?,'P37 synthetic style','A2','FS',?,2026,'AUTUMN','OWN',?,'DRAFT') RETURNING id
                """, Long.class, prefix, f.category(), f.sizeGroup());
        try {
            // 25,000 synthetic shared colors on two styles exercise 50,001 child rows without 50,001 parents.
            seedInBatches("p37-color-master-fixture", 25_000, 1, (from, to) -> jdbc.update("""
                    INSERT INTO bas_color(code,name)
                    SELECT codes.code, ? || '-' || codes.code
                    FROM (
                      SELECT substr('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ', n / 1296 + 1, 1)
                          || substr('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ', n / 36 % 36 + 1, 1)
                          || substr('0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ', n % 36 + 1, 1) AS code
                      FROM generate_series(0,46655) n
                    ) codes
                    WHERE NOT EXISTS (SELECT 1 FROM bas_color existing WHERE existing.code=codes.code)
                    ORDER BY codes.code LIMIT ?
                    """, prefix, to - from + 1));
            seedInBatches("p37-style-color-fixture", 25_000, 2, (from, to) -> jdbc.update("""
                    WITH colors AS (
                      SELECT id,row_number() OVER (ORDER BY id) AS n FROM bas_color WHERE name LIKE ?
                    )
                    INSERT INTO sty_style_color(style_id,color_id,seq)
                    SELECT parents.id,c.id,c.n+1
                    FROM colors c CROSS JOIN (VALUES (CAST(? AS bigint)),(CAST(? AS bigint))) parents(id)
                    WHERE c.n>=? AND c.n<=?
                    """, prefix + "-%", f.style(), second, from, to));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style_color WHERE style_id IN (?,?)", Long.class,
                    f.style(), second)).isEqualTo(50_001L);
            authenticate(exporter);
            StyleQuery query = new StyleQuery(); query.setIds(List.of(f.style(), second));
            var captured = rejectedSql(() -> styles.export(query));
            sqlEvidence("p37-style-color-overflow", 2, 50_001, captured);
            assertBounded(captured, "sty_style_color", "order by style_id asc,seq asc,id asc");
            assertThat(queries(captured, "sty_style_sku")).as("no subsequent child dataset is loaded after overflow").isEmpty();
            HttpResponse<byte[]> reply = rejectedExport(exporter, "/api/styles/export?ids=" + f.style() + "," + second, "款式颜色");
            evidence("p37-style-color-overflow", 2, 50_001, reply, captured);
        } finally {
            cleanupInBatches("p37-style-color-cleanup", () -> jdbc.update("""
                    DELETE FROM sty_style_color WHERE id IN (
                      SELECT id FROM sty_style_color WHERE style_id IN (?,?)
                        AND color_id IN (SELECT id FROM bas_color WHERE name LIKE ?) ORDER BY id LIMIT 5000)
                    """, f.style(), second, prefix + "-%"));
            cleanupInBatches("p37-color-master-cleanup", () -> jdbc.update("""
                    DELETE FROM bas_color WHERE id IN (
                      SELECT id FROM bas_color WHERE name LIKE ? ORDER BY id LIMIT 5000)
                    """, prefix + "-%"));
            jdbc.update("DELETE FROM sty_style WHERE id=?", second);
        }
    }

    @Test void legalMultiSheetExportsKeepRowsOrderingFormulaEscapingAndPhysicalCostColumnPermissions() throws Exception {
        Fixture f = fixture();
        Account hidden = custom(f.company(), "ALL", "basic:material:export", "style:style:export");
        Account cost = custom(f.company(), "ALL", "basic:material:export", "style:style:export", "techpack:cost:query");
        String materialName = "=CMD|' synthetic'!A0", styleName = "+synthetic-formula";
        jdbc.update("UPDATE bas_material SET name=? WHERE id=?", materialName, f.material());
        jdbc.update("UPDATE sty_style SET name=?,remark='@synthetic-formula' WHERE id=?", styleName, f.style());
        String secondMaterialSku = f.materialCode() + "-Z";
        jdbc.update("INSERT INTO bas_material_sku(material_id,code,color_id,spec_code,price,remark) VALUES(?,?,?,'P37',22.22,'-synthetic-formula')",
                f.material(), secondMaterialSku, f.color());
        long nextColor = TestColors.insert(jdbc, "P37 legal " + suffix());
        String nextCode = jdbc.queryForObject("SELECT code FROM bas_color WHERE id=?", String.class, nextColor);
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,2,'L')", f.sizeGroup());
        jdbc.update("INSERT INTO sty_style_color(style_id,color_id,seq) VALUES(?,?,2)", f.style(), nextColor);
        // Insert reverse business order: the exported matrix must still be color sequence then size-group sequence.
        for (String size : List.of("L", "M")) {
            jdbc.update("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,?)",
                    f.style(), f.styleCode() + "-" + nextCode + "-" + size, nextColor, size);
        }
        jdbc.update("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES(?,?,?,'L')",
                f.style(), f.styleCode() + "-" + f.colorCode() + "-L", f.color());
        List<Map<String, Object>> workbooks = new ArrayList<>();
        for (Account actor : List.of(hidden, cost)) {
            boolean prices = actor.id() == cost.id();
            HttpResponse<byte[]> materialReply = raw(actor, "GET", "/api/materials/export?ids=" + f.material(), null, null, null);
            HttpResponse<byte[]> styleReply = raw(actor, "GET", "/api/styles/export?ids=" + f.style(), null, null, null);
            try (Workbook material = workbook(materialReply); Workbook style = workbook(styleReply)) {
                assertThat(material.getNumberOfSheets()).isEqualTo(2);
                assertThat(material.getSheetAt(0).getSheetName()).isEqualTo("物料信息");
                assertThat(material.getSheetAt(1).getSheetName()).isEqualTo("物料信息SKU");
                assertThat(material.getSheetAt(0).getLastRowNum()).isEqualTo(1);
                assertThat(material.getSheetAt(1).getLastRowNum()).isEqualTo(2);
                assertThat(column(material.getSheetAt(1), "物料SKU编码")).containsExactly(f.materialSkuCode(), secondMaterialSku);
                assertThat(cell(material.getSheetAt(0), 1, "物料名称").getStringCellValue()).isEqualTo("'" + materialName);
                assertThat(cell(material.getSheetAt(1), 2, "SKU信息备注").getStringCellValue()).isEqualTo("'-synthetic-formula");
                assertThat(style.getNumberOfSheets()).isEqualTo(2);
                assertThat(style.getSheetAt(0).getSheetName()).isEqualTo("款式");
                assertThat(style.getSheetAt(1).getSheetName()).isEqualTo("导出 SKU");
                assertThat(style.getSheetAt(0).getLastRowNum()).isEqualTo(1);
                assertThat(style.getSheetAt(1).getLastRowNum()).isEqualTo(4);
                assertThat(column(style.getSheetAt(1), "SKU 码")).containsExactly(
                        f.styleCode() + "-" + f.colorCode() + "-M", f.styleCode() + "-" + f.colorCode() + "-L",
                        f.styleCode() + "-" + nextCode + "-M", f.styleCode() + "-" + nextCode + "-L");
                assertThat(cell(style.getSheetAt(0), 1, "颜色").getStringCellValue()).isEqualTo(f.colorCode() + "," + nextCode);
                assertThat(cell(style.getSheetAt(0), 1, "款式名称").getStringCellValue()).isEqualTo("'" + styleName);
                assertThat(cell(style.getSheetAt(0), 1, "备注").getStringCellValue()).isEqualTo("'@synthetic-formula");
                if (prices) {
                    assertThat(headers(material.getSheetAt(0))).contains("成本价", "其他价格", "倍率");
                    assertThat(headers(material.getSheetAt(1))).contains("SKU成本价");
                    assertThat(headers(style.getSheetAt(0))).contains("参考成本价", "财务成本价");
                    assertThat(decimal(cell(material.getSheetAt(0), 1, "成本价"))).isEqualByComparingTo("12.34");
                    assertThat(decimal(cell(material.getSheetAt(1), 1, "SKU成本价"))).isEqualByComparingTo("13.37");
                    assertThat(decimal(cell(style.getSheetAt(0), 1, "参考成本价"))).isEqualByComparingTo("456.78");
                } else {
                    assertThat(headers(material.getSheetAt(0))).doesNotContain("成本价", "其他价格", "倍率");
                    assertThat(headers(material.getSheetAt(1))).doesNotContain("SKU成本价");
                    assertThat(headers(style.getSheetAt(0))).doesNotContain("参考成本价", "财务成本价");
                }
                noFormulas(material); noFormulas(style);
                Path directory = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(directory);
                String role = prices ? "with-cost" : "without-cost";
                Files.write(directory.resolve("p37-material-" + role + ".xlsx"), materialReply.body());
                Files.write(directory.resolve("p37-style-" + role + ".xlsx"), styleReply.body());
                workbooks.add(map("costPermission", prices, "materialHeaders", headers(material.getSheetAt(0)),
                        "materialSkuHeaders", headers(material.getSheetAt(1)), "styleHeaders", headers(style.getSheetAt(0)),
                        "materialSkuRows", material.getSheetAt(1).getLastRowNum(), "styleSkuRows", style.getSheetAt(1).getLastRowNum(),
                        "materialSkuCodes", column(material.getSheetAt(1), "物料SKU编码"), "styleSkuCodes", column(style.getSheetAt(1), "SKU 码"),
                        "escapedMaterialName", cell(material.getSheetAt(0), 1, "物料名称").getStringCellValue(),
                        "escapedStyleName", cell(style.getSheetAt(0), 1, "款式名称").getStringCellValue()));
            }
        }
        writeEvidence("p37-legal-exports.json", map("workbooks", workbooks));
    }

    /** Setup/cleanup keep all production constraints and timeouts; only fixture statements are bounded. */
    private void seedInBatches(String name, int upper, int multiplier, IntBinaryOperator insert) throws Exception {
        List<Map<String, Object>> batches = new ArrayList<>();
        int inserted = 0;
        for (int from = 1; from <= upper; from += 2_500) {
            int to = Math.min(upper, from + 2_499);
            long started = System.nanoTime();
            int changed = insert.applyAsInt(from, to);
            assertThat(changed).isEqualTo((to - from + 1) * multiplier);
            inserted += changed;
            batches.add(map("fromInclusive", from, "toInclusive", to, "insertedRows", changed,
                    "elapsedMs", (System.nanoTime() - started) / 1_000_000));
        }
        assertThat(inserted).isEqualTo(upper * multiplier);
        writeEvidence(name + ".json", map("batches", batches, "insertedRows", inserted,
                "statementTimeout", jdbc.queryForObject("SHOW statement_timeout", String.class)));
    }

    private void cleanupInBatches(String name, IntSupplier delete) throws Exception {
        List<Map<String, Object>> batches = new ArrayList<>();
        // At most 50,000 owned fixture rows; the final empty batch proves cleanup is complete.
        for (int batch = 0; batch <= 10; batch++) {
            long started = System.nanoTime();
            int changed = delete.getAsInt();
            assertThat(changed).isBetween(0, 5_000);
            batches.add(map("deletedRows", changed, "elapsedMs", (System.nanoTime() - started) / 1_000_000));
            if (changed == 0) {
                writeEvidence(name + ".json", map("batches", batches,
                        "statementTimeout", jdbc.queryForObject("SHOW statement_timeout", String.class)));
                return;
            }
        }
        throw new IllegalStateException("Synthetic export fixture cleanup exceeded its row budget");
    }

    private HttpResponse<byte[]> rejectedExport(Account actor, String path, String label) throws Exception {
        HttpResponse<byte[]> response = raw(actor, "GET", path, null, null, null);
        JsonNode body = node(response);
        assertThat(body.path("code").intValue()).isEqualTo(1000);
        assertThat(body.path("msg").stringValue()).contains(label, "50000 行");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        return response;
    }

    private void exactMaterialLimit(Fixture fixture, Account exporter, long second, String prefix) throws Exception {
        String removed = prefix + "-" + second + "-25000";
        assertThat(jdbc.update("DELETE FROM bas_material_sku WHERE material_id=? AND code=?", second, removed)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bas_material_sku WHERE material_id IN (?,?)", Long.class,
                fixture.material(), second)).isEqualTo(50_000L);
        HttpResponse<byte[]> reply = raw(exporter, "GET", "/api/materials/export?ids=" + fixture.material() + "," + second, null, null, null);
        assertThat(reply.statusCode()).isEqualTo(200);
        assertThat(reply.headers().firstValue("Content-Disposition").orElse("")).contains("filename*=UTF-8''");
        Path directory = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(directory);
        Path downloaded = directory.resolve("p37-material-exact-limit.xlsx");
        Files.write(downloaded, reply.body()); // Preserve the actual HTTP artifact even if verification fails.
        long importEntryLimit = ZipSecureFile.getMaxEntrySize();

        // The server's import parser keeps its 16 MiB entry ceiling. This test verifies its own known export
        // with a separate bounded ZipFile and secure SAX, without changing any POI global security setting.
        List<SheetCounter> sheets = new ArrayList<>();
        try (ExportArchive archive = new ExportArchive(downloaded)) {
            Map<String, String> targets = new HashMap<>();
            parse(archive, "xl/_rels/workbook.xml.rels", new DefaultHandler() {
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    if ("Relationship".equals(local) && attributes.getValue("Type").endsWith("/worksheet")) {
                        assertThat(attributes.getValue("TargetMode")).isNotEqualTo("External");
                        String target = attributes.getValue("Target");
                        assertThat(target).matches("worksheets/sheet[1-9][0-9]*\\.xml");
                        assertThat(targets.put(attributes.getValue("Id"), "xl/" + target)).isNull();
                    }
                }
            });
            List<SheetReference> references = new ArrayList<>();
            parse(archive, "xl/workbook.xml", new DefaultHandler() {
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    if ("sheet".equals(local)) references.add(new SheetReference(attributes.getValue("name"), attributes.getValue("r:id")));
                }
            });
            assertThat(references).hasSize(2);
            SharedStrings strings;
            try (InputStream input = archive.open("xl/sharedStrings.xml")) { strings = new ReadOnlySharedStringsTable(input); }
            for (SheetReference reference : references) {
                SheetCounter counter = new SheetCounter(reference.name());
                assertThat(targets).containsKey(reference.relationship());
                parse(archive, targets.get(reference.relationship()),
                        new XSSFSheetXMLHandler(null, strings, counter, new DataFormatter(Locale.ROOT), false));
                sheets.add(counter);
            }
        }
        assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(importEntryLimit).isEqualTo(16L * 1024 * 1024);
        assertThat(sheets).hasSize(2);
        SheetCounter master = sheets.getFirst(), sku = sheets.get(1);
        assertThat(master.name).isEqualTo("物料信息");
        assertThat(master.dataRows).isEqualTo(2);
        assertThat(master.headers.values()).doesNotContain("成本价", "其他价格", "倍率");
        assertThat(sku.name).isEqualTo("物料信息SKU");
        assertThat(sku.dataRows).isEqualTo(50_000);
        assertThat(sku.codes).hasSize(50_000).contains(fixture.materialSkuCode()).doesNotContain(removed);
        assertThat(sku.headers.values()).doesNotContain("SKU成本价");
        Set<String> expected = new HashSet<>(); expected.add(fixture.materialSkuCode());
        for (long material : List.of(fixture.material(), second)) {
            for (int index = 1; index <= 25_000; index++) expected.add(prefix + "-" + material + "-" + index);
        }
        expected.remove(removed);
        Set<String> missing = new HashSet<>(expected); missing.removeAll(sku.codes);
        Set<String> unexpected = new HashSet<>(sku.codes); unexpected.removeAll(expected);
        assertThat(missing).as("no SKU is silently truncated at the accepted limit").isEmpty();
        assertThat(unexpected).as("the accepted workbook contains only the requested SKU rows").isEmpty();
        writeEvidence("p37-material-exact-limit.json", map("databaseRows", 50_000, "masterRows", master.dataRows,
                "skuRows", sku.dataRows, "uniqueSkuCodes", sku.codes.size(), "missingSkuCodes", missing.size(),
                "unexpectedSkuCodes", unexpected.size(), "masterHeaders", master.headers.values(), "skuHeaders", sku.headers.values(),
                "downloadBytes", reply.body().length, "requestId", reply.headers().firstValue("X-Request-Id").orElse(""),
                "importEntryLimitUnchanged", importEntryLimit, "validatorMaxZipEntries", ExportArchive.MAX_ENTRIES,
                "validatorMaxEntryBytes", ExportArchive.MAX_ENTRY_BYTES, "validatorMaxExpandedBytes", ExportArchive.MAX_EXPANDED_BYTES,
                "verification", "Real HTTP XLSX read completely with bounded JDK ZipFile and secure SAX/POI sheet events; capacity boundary only, not concurrency or disconnect proof."));
    }

    private record SheetReference(String name, String relationship) { }

    private static void parse(ExportArchive archive, String name, DefaultHandler handler) throws Exception {
        try (InputStream input = archive.open(name)) {
            var parser = XMLHelper.newXMLReader();
            parser.setContentHandler(handler);
            parser.parse(new InputSource(input));
        }
    }

    /** Test-only verifier for the known synthetic download; independent of server upload/import limits. */
    private static final class ExportArchive implements AutoCloseable {
        private static final int MAX_ENTRIES = 32;
        private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;
        private static final long MAX_EXPANDED_BYTES = 64L * 1024 * 1024;
        private final ZipFile zip;
        private long expanded;
        private ExportArchive(Path path) throws IOException {
            assertThat(Files.size(path)).isLessThanOrEqualTo(16L * 1024 * 1024);
            zip = new ZipFile(path.toFile());
            try {
                assertThat(zip.size()).isBetween(1, MAX_ENTRIES);
                Set<String> names = new HashSet<>();
                long declared = 0;
                for (ZipEntry entry : zip.stream().toList()) {
                    assertThat(names.add(entry.getName())).as("ZIP entry names must be unique").isTrue();
                    assertThat(entry.getSize()).isBetween(0L, MAX_ENTRY_BYTES);
                    declared += entry.getSize();
                }
                assertThat(declared).isLessThanOrEqualTo(MAX_EXPANDED_BYTES);
            } catch (RuntimeException | AssertionError failure) {
                zip.close(); throw failure;
            }
        }
        private InputStream open(String name) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            assertThat(entry).as("export entry %s exists", name).isNotNull();
            return new FilterInputStream(zip.getInputStream(entry)) {
                private long bytes;
                private void consume(int count) throws IOException {
                    if (count > 0 && ((bytes += count) > MAX_ENTRY_BYTES || (expanded += count) > MAX_EXPANDED_BYTES))
                        throw new IOException("Synthetic export exceeds verifier's bounded expansion budget");
                }
                @Override public int read() throws IOException {
                    int value = in.read(); consume(value < 0 ? 0 : 1); return value;
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = in.read(bytes, offset, length); consume(count); return count;
                }
            };
        }
        @Override public void close() throws IOException { zip.close(); }
    }

    private static final class SheetCounter implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final String name;
        private final Map<Integer, String> headers = new TreeMap<>();
        private final Set<String> codes = new HashSet<>();
        private int currentRow;
        private int dataRows;
        private SheetCounter(String name) { this.name = name; }
        @Override public void startRow(int rowNum) { currentRow = rowNum; }
        @Override public void endRow(int rowNum) { if (rowNum > 0) dataRows++; }
        @Override public void cell(String cellReference, String formattedValue, Comment comment) {
            int column = new CellReference(cellReference).getCol();
            if (currentRow == 0) headers.put(column, formattedValue);
            else if ("物料SKU编码".equals(headers.get(column))) codes.add(formattedValue);
        }
    }

    private SqlStatementCapture.Captured<BizException> rejectedSql(Runnable export) {
        return SqlStatementCapture.capture(() -> {
            try { export.run(); } catch (BizException error) { return error; }
            throw new AssertionError("Expected bounded export to reject the real excessive child rows");
        });
    }

    private static List<String> queries(SqlStatementCapture.Captured<?> captured, String table) {
        return captured.statements().stream().map(s -> s.getSql().replaceAll("\\s+", " ").replaceAll("\\s*,\\s*", ",").strip().toLowerCase(Locale.ROOT))
                .filter(sql -> sql.matches(".*\\bfrom " + table + "\\b.*")).toList();
    }

    private static void assertBounded(SqlStatementCapture.Captured<?> captured, String table, String order) {
        assertThat(Excel.MAX_EXPORT_ROWS).isEqualTo(50_000); // Never reduce the production cap for this regression.
        assertThat(queries(captured, table)).singleElement().satisfies(sql -> {
            assertThat(sql).endsWith("limit 50001").contains(order);
        });
    }

    private void evidence(String name, int parentCount, int childCount, HttpResponse<byte[]> reply,
                          SqlStatementCapture.Captured<?> captured) throws Exception {
        writeEvidence(name + ".json", map("parentRows", parentCount, "childRows", childCount, "perSheetLimit", Excel.MAX_EXPORT_ROWS,
                "response", node(reply), "sql", captured.statements().stream().map(s -> s.getSql().replaceAll("\\s+", " ").strip()).toList(),
                "limitation", "Bounded in-memory multi-sheet lists and complete byte[] remain; not full HTTP streaming."));
    }

    private void sqlEvidence(String name, int parentCount, int childCount, SqlStatementCapture.Captured<BizException> captured) throws Exception {
        writeEvidence(name + "-sql.json", map("parentRows", parentCount, "childRows", childCount,
                "perSheetLimit", Excel.MAX_EXPORT_ROWS, "fixture", "Extreme synthetic child-row capacity boundary; not concurrency evidence.",
                "exceptionCode", captured.result().getCode(), "exceptionMessage", captured.result().getMessage(),
                "sql", captured.statements().stream().map(s -> s.getSql().replaceAll("\\s+", " ").strip()).toList()));
    }

    private void writeEvidence(String name, Object body) throws Exception {
        Path directory = Path.of("test-artifacts/full-audit/s2"); Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(body));
    }

    private static Workbook workbook(HttpResponse<byte[]> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("filename*=UTF-8''");
        return WorkbookFactory.create(new ByteArrayInputStream(response.body()));
    }

    private static List<String> headers(Sheet sheet) {
        List<String> result = new ArrayList<>();
        sheet.getRow(0).forEach(cell -> result.add(cell.getStringCellValue()));
        return result;
    }

    private static Cell cell(Sheet sheet, int row, String header) {
        int column = headers(sheet).indexOf(header);
        assertThat(column).as("header %s is physically present", header).isNotNegative();
        return sheet.getRow(row).getCell(column);
    }

    private static List<String> column(Sheet sheet, String header) {
        List<String> result = new ArrayList<>();
        for (int row = 1; row <= sheet.getLastRowNum(); row++) result.add(cell(sheet, row, header).getStringCellValue());
        return result;
    }

    private static BigDecimal decimal(Cell cell) { return BigDecimal.valueOf(cell.getNumericCellValue()); }

    private static void noFormulas(Workbook workbook) {
        for (Sheet sheet : workbook) for (var row : sheet) for (Cell cell : row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
    }
}
