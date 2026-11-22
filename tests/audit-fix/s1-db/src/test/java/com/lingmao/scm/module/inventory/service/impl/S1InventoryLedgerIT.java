package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.inventory.dto.InvDocCreateReq;
import com.lingmao.scm.module.inventory.dto.InvDocItemReq;
import com.lingmao.scm.module.inventory.entity.InvDocType;
import com.lingmao.scm.module.inventory.service.InvDocService;
import com.lingmao.scm.security.LoginUser;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 普通 SQL 也不能重写库存账；真正的审核和反审核只追加原事件及其冲销。 */
@IntegrationTest
class S1InventoryLedgerIT {
    private static final List<String> PERMISSIONS = List.of("inventory:doc:list", "inventory:doc:add",
            "inventory:doc:audit", "techpack:cost:query");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired InvDocService documents;
    @Autowired Flyway flyway;
    @Autowired Clock clock;
    @Autowired JsonMapper json;

    @AfterEach
    void logout() { TestIdentity.clear(); }

    @Test
    void ordinaryUpdateDeleteAndTruncateAreRejectedWithoutChangingOriginalEvent() {
        long doc = postedDocument();
        long original = jdbc.queryForObject("SELECT id FROM inv_stock_log WHERE inv_doc_id=?", Long.class, doc);
        String before = jdbc.queryForObject("SELECT row_to_json(l)::text FROM inv_stock_log l WHERE id=?", String.class, original);
        assertBlocked("UPDATE inv_stock_log SET change_qty=9,amount=99 WHERE id=?", original);
        assertBlocked("DELETE FROM inv_stock_log WHERE id=?", original);
        assertBlocked("TRUNCATE inv_stock_log");
        assertThat(jdbc.queryForObject("SELECT row_to_json(l)::text FROM inv_stock_log l WHERE id=?", String.class, original))
                .isEqualTo(before);
    }

    @Test
    void realAuditAndReverseAuditAppendExactOppositeEvents() throws Exception {
        long doc = postedDocument();
        long original = jdbc.queryForObject("SELECT id FROM inv_stock_log WHERE inv_doc_id=?", Long.class, doc);
        documents.act(doc, "unaudit", new DocActionReq(documents.detail(doc).getVersion(), "S1 反审验证"));
        var events = jdbc.queryForList("""
                SELECT id,change_qty,amount,balance_qty,reversal_of_log_id
                FROM inv_stock_log WHERE inv_doc_id=? ORDER BY id
                """, doc);
        assertThat(events).hasSize(2);
        assertThat(events.getFirst().get("reversal_of_log_id")).isNull();
        assertThat(events.getLast().get("reversal_of_log_id")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT change_qty FROM inv_stock_log WHERE id=?", BigDecimal.class, original))
                .isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT amount FROM inv_stock_log WHERE id=?", BigDecimal.class, original))
                .isEqualByComparingTo("6");
        assertThat(jdbc.queryForObject("SELECT sum(change_qty) FROM inv_stock_log WHERE inv_doc_id=?", BigDecimal.class, doc))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM inv_stock_log WHERE inv_doc_id=?", BigDecimal.class, doc))
                .isEqualByComparingTo("0");
        assertBlocked("UPDATE inv_stock_log SET reversal_of_log_id=NULL WHERE id=?", events.getLast().get("id"));
        Path artifact = Path.of("target", "a1-production", "s1-inventory-ledger-reversal.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, json.writeValueAsString(events));
    }

    @Test
    void migrationCanBeInstalledAgainAndDoesNotLeavePendingMigrations() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/R__inventory_ledger_append_only.sql")) {
            assertThat(stream).isNotNull();
            String migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (int i = 0; i < 2; i++) {
                transactions.executeWithoutResult(status -> jdbc.execute(migration));
            }
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger
                WHERE tgrelid='inv_stock_log'::regclass AND tgname='inventory_ledger_append_only'
                  AND tgenabled='O' AND NOT tgisinternal
                """, Integer.class)).isEqualTo(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertBlocked("UPDATE inv_stock_log SET doc_no=doc_no WHERE false");
    }

    private void assertBlocked(String statement, Object... args) {
        // 即使守卫回归失效，破坏性断言也仅在必回滚事务中运行。
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.update(statement, args);
        })).isInstanceOfSatisfying(DataAccessException.class, failure ->
                assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, sql -> {
                    assertThat(sql.getSQLState()).isEqualTo("55000");
                    assertThat(sql.getMessage()).contains("库存流水只允许追加");
                }));
    }

    private long postedDocument() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long company = jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,'S1 库存账','S1') RETURNING id",
                Long.class, suffix.substring(0, 6).toUpperCase(Locale.ROOT));
        long user = jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES (?,'x','S1 库存账') RETURNING id",
                Long.class, "s1-ledger-" + suffix);
        TestIdentity.grant(jdbc, user, PERMISSIONS);
        TestIdentity.company(company);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user, "s1-ledger"), null,
                PERMISSIONS.stream().map(SimpleGrantedAuthority::new).toList()));
        long warehouse = jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES (?,'S1 库存账','MATERIAL') RETURNING id",
                Long.class, "S1" + suffix);
        long category = jdbc.queryForObject("INSERT INTO bas_material_category(name,level) VALUES (?,1) RETURNING id", Long.class, suffix);
        long material = jdbc.queryForObject("""
                INSERT INTO bas_material(code,name,category_id,material_type,stock_unit,purchase_unit)
                VALUES (?,'S1 面料',?,'FABRIC','METER','METER') RETURNING id
                """, Long.class, "S1M" + suffix, category);
        long sku = jdbc.queryForObject("INSERT INTO bas_material_sku(material_id,code) VALUES (?,?) RETURNING id",
                Long.class, material, "S1K" + suffix);
        var line = new InvDocItemReq();
        line.setMaterialSkuId(sku); line.setQty(new BigDecimal("2")); line.setPrice(new BigDecimal("3"));
        var request = new InvDocCreateReq();
        request.setCompanyId(company); request.setToWarehouseId(warehouse);
        request.setDocType(InvDocType.OTHER_IN); request.setDocDate(LocalDate.now(clock));
        request.setReason("OTHER"); request.setItems(List.of(line));
        long doc = documents.create(request).id();
        documents.act(doc, "audit", new DocActionReq(documents.detail(doc).getVersion(), null));
        return doc;
    }
}
