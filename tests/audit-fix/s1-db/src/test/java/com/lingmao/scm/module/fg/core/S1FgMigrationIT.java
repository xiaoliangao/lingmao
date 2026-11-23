package com.lingmao.scm.module.fg.core;

import com.lingmao.scm.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** V46 原始结构与历史数据真实升级，不以空库迁移冒充旧账兼容。 */
@IntegrationTest
class S1FgMigrationIT {
    @Autowired DataSource dataSource;
    @Value("${spring.datasource.password:}") String databasePassword;
    @Value("${audit.fg.legacy-negative-control:false}") boolean legacyNegativeControl;

    /** Exact resources for the historical V46 -> V57 contract; later domains run in their full-schema tests. */
    @TempDir Path historicalMigrations;
    private static final List<String> HISTORICAL_RESOURCES = List.of(
            "V1__init.sql",
            "V2__dict_data_unique_value.sql",
            "V3__sys_user.sql",
            "V4__sys_menu.sql",
            "V5__sys_role.sql",
            "V6__sys_oper_log.sql",
            "V7__sys_menu_admin_only.sql",
            "V8__sys_framework.sql",
            "V9__biz_doc_log_attachment.sql",
            "V10__basic_base.sql",
            "V11__sys_dict_user.sql",
            "V12__supplier.sql",
            "V13__basic_material_warehouse.sql",
            "V14__style.sql",
            "V15__price.sql",
            "V16__menu_master.sql",
            "V17__menu_batch34.sql",
            "V18__craft_standard_hidden.sql",
            "V19__biz_notice_approval.sql",
            "V20__techpack.sql",
            "V21__inventory.sql",
            "V22__order.sql",
            "V23__purchase.sql",
            "V24__material_type_reserve_pct.sql",
            "V25__production.sql",
            "V26__audit_remediation.sql",
            "V27__production_write_guards.sql",
            "V28__online_flow_indexes.sql",
            "V28__online_flow_indexes.sql.conf",
            "V29__purchase_posting_precision.sql",
            "V30__shared_security_state.sql",
            "V31__security_schema_comments.sql",
            "V32__durable_jobs.sql",
            "V33__upload_file_lifecycle.sql",
            "V33__upload_file_lifecycle.sql.conf",
            "V34__export_storage_identity.sql",
            "V34__export_storage_identity.sql.conf",
            "V35__file_import_receipts.sql",
            "V36__append_only_field_audit.sql",
            "V46__fg.sql",
            "V57__fg_company_ledger_guards.sql",
            "R__fg_field_audit.sql",
            "R__home_read_model_indexes.sql",
            "R__home_read_model_indexes.sql.conf",
            "R__inventory_ledger_append_only.sql",
            "R__security_field_audit.sql",
            "R__security_field_audit.sql.conf"
    );

    /** 同一断言可对发布 V46 做真实失败负控；默认始终验收 V57，绝不修改生产守卫。 */
    @Test
    void ledgerMutationIsRejectedAtTheDatabaseBoundary() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);long doc=doc(jdbc,f.company(),f.warehouse(),"APPROVED");
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,1,?,3)",doc,f.sku());
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,qty) VALUES (?,?,3)",f.warehouse(),f.sku());
            event(jdbc,doc,f,3,3);
            if (!legacyNegativeControl) latest(jdbc).migrate();
            Throwable failure=catchThrowable(() -> jdbc.update("UPDATE fg_stock_log SET change_qty=change_qty+1 WHERE fg_doc_id=?",doc));
            Throwable cause=failure;
            while(cause != null && cause.getCause() != null) cause=cause.getCause();
            String sqlState=cause instanceof SQLException sql ? sql.getSQLState() : null;
            writeEvidence("ledger-mutation-boundary", "{\"schema\":\""+(legacyNegativeControl?"V46":"V57")
                    +"\",\"rejected\":"+(failure != null)+",\"sqlState\":\""+sqlState+"\",\"ledgerQty\":"
                    +jdbc.queryForObject("SELECT SUM(change_qty) FROM fg_stock_log",Long.class)+"}");
            assertThat(failure).as("published ledger must reject direct UPDATE").isNotNull();
            assertThat(sqlState).isEqualTo("55000");
            assertThat(jdbc.queryForObject("SELECT SUM(change_qty) FROM fg_stock_log",Long.class)).isEqualTo(3);
        });
    }

    @Test
    void aCleanPublishedV46SchemaUpgradesAndSecondMigrateIsANoOp() throws Exception {
        withV46((jdbc) -> {
            Flyway upgraded=latest(jdbc);
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgraded.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_trigger WHERE tgrelid='fg_stock_log'::regclass AND tgname='fg_ledger_append_only' AND tgenabled='O'",Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void provableHistoricalReversalIsPairedWithoutRewritingOldQuantitiesOrBalances() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);long doc=doc(jdbc,f.company(),f.warehouse(),"APPROVED");
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,1,?,3)",doc,f.sku());
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,qty) VALUES (?,?,3)",f.warehouse(),f.sku());
            event(jdbc,doc,f,4,4);event(jdbc,doc,f,-4,0);event(jdbc,doc,f,3,3);
            String before=snapshot(jdbc);
            assertThat(latest(jdbc).migrate().migrationsExecuted).isEqualTo(1);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE posting_version IS NOT NULL",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT company_id FROM fg_stock",Long.class)).isEqualTo(f.company());
            assertThat(jdbc.queryForObject("SELECT movement_revision FROM fg_stock",Long.class)).isEqualTo(3);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log WHERE reversal_of_log_id IS NOT NULL",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fg_stock_log r JOIN fg_stock_log o ON r.reversal_of_log_id=o.id WHERE r.change_qty=-o.change_qty AND r.company_id=o.company_id",Integer.class)).isEqualTo(1);
            writeEvidence("provable-history",before);
        });
    }

    @Test
    void sharedCompanyHistoryIsRejectedWithoutAssigningStockToAnArbitraryCompany() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);long other=company(jdbc);long a=doc(jdbc,f.company(),f.warehouse(),"APPROVED"),b=doc(jdbc,other,f.warehouse(),"APPROVED");
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,qty) VALUES (?,?,10)",f.warehouse(),f.sku());
            event(jdbc,a,f,5,5);event(jdbc,b,f,5,10);
            String before=snapshot(jdbc);
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("shared stock history contains multiple companies");
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='fg_stock' AND column_name='company_id'",Integer.class)).isZero();
            writeEvidence("ambiguous-history-rejected",before);
        });
    }

    @Test
    void balanceWithoutLedgerAndWrongSourceCompanyAreBothRejectedBeforeAnyUpgrade() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,qty) VALUES (?,?,2)",f.warehouse(),f.sku());
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("stock balance has no complete matching ledger");
            jdbc.update("DELETE FROM fg_stock");
            long user=jdbc.queryForObject("INSERT INTO sys_user(username,password,nickname) VALUES ('fg-old-user','unusable','S1 FG') RETURNING id",Long.class);
            long order=jdbc.queryForObject("INSERT INTO ord_order(no,company_id,order_type,order_date,required_date,sales_id,merch_id) VALUES ('OLD-ORDER',?,'BULK',current_date,current_date,?,?) RETURNING id",Long.class,f.company(),user,user);
            jdbc.update("INSERT INTO fg_doc(no,company_id,kind,doc_type,from_warehouse_id,order_id,doc_date) VALUES ('WRONG-COMPANY',?,'OUT','SHIP_OUT',?,?,current_date)",company(jdbc),f.warehouse(),order);
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("source document company mismatch");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='fg_stock' AND column_name='company_id'",Integer.class)).isZero();
        });
    }

    @Test
    void missingSkuAndWrongQuantityOnAnApprovedDocumentBothPreventMigration() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);long doc=doc(jdbc,f.company(),f.warehouse(),"APPROVED");
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,1,?,4)",doc,f.sku());
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,qty) VALUES (?,?,3)",f.warehouse(),f.sku());
            event(jdbc,doc,f,3,3);
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("approved document items and active events differ");
            jdbc.update("UPDATE fg_doc_item SET qty=3 WHERE doc_id=?",doc);
            long secondSku=jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) SELECT style_id,'OLD-FG-L',color_id,'L' FROM sty_style_sku WHERE id=? RETURNING id",Long.class,f.sku());
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,2,?,1)",doc,secondSku);
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("approved document items and active events differ");
            assertThat(jdbc.queryForObject("SELECT sum(change_qty) FROM fg_stock_log",Long.class)).isEqualTo(3);
        });
    }

    @Test
    void unsupportedHistoricalLocationRequiresReconciliationWithoutRewritingTheKey() throws Exception {
        withV46(jdbc -> {
            Fixture f=fixture(jdbc);long doc=doc(jdbc,f.company(),f.warehouse(),"APPROVED");
            jdbc.update("INSERT INTO fg_doc_item(doc_id,seq,style_sku_id,qty) VALUES (?,1,?,3)",doc,f.sku());
            jdbc.update("INSERT INTO fg_stock(warehouse_id,style_sku_id,location_id,qty) VALUES (?,?,1,3)",f.warehouse(),f.sku());
            event(jdbc,doc,f,3,3);
            jdbc.update("UPDATE fg_stock_log SET location_id=1 WHERE fg_doc_id=?",doc);
            assertThatThrownBy(() -> latest(jdbc).migrate()).hasStackTraceContaining("nonzero locations have no matching document item model");
            assertThat(jdbc.queryForObject("SELECT location_id FROM fg_stock",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT location_id FROM fg_stock_log",Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='fg_stock' AND column_name='company_id'",Integer.class)).isZero();
        });
    }

    private void withV46(Consumer<JdbcTemplate> work) throws Exception {
        String database="s1_fg_"+UUID.randomUUID().toString().replace("-","")+"_test";
        JdbcTemplate admin=new JdbcTemplate(dataSource);
        String url,user;
        try(Connection original=dataSource.getConnection()) {
            String current=original.getMetaData().getURL();
            url=current.substring(0,current.lastIndexOf('/')+1)+database;
            user=original.getMetaData().getUserName();
        }
        admin.execute("CREATE DATABASE "+database);
        try {
            // Flyway 的迁移锁与 CONCURRENTLY DDL 必须能获得各自连接，不能用 SingleConnectionDataSource。
            DriverManagerDataSource separate=new DriverManagerDataSource(url,user,databasePassword);
            Flyway old=configured(separate).target("46").load();
            old.migrate();work.accept(new JdbcTemplate(separate));
        } finally { admin.execute("DROP DATABASE "+database+" WITH (FORCE)"); }
    }
    private Flyway latest(JdbcTemplate jdbc) {
        String schema=jdbc.queryForObject("SELECT current_schema()",String.class);
        // This fixture proves the published V46 -> V57 ledger upgrade, not later independent domains.
        return configured(jdbc.getDataSource()).schemas(schema).defaultSchema(schema).target("57").load();
    }
    private FluentConfiguration configured(DataSource source) {
        // Flyway repeatables are not bounded by target(46/57). Stage the exact historical inputs,
        // including their nontransactional sidecars, rather than executing future-domain triggers.
        try {
            for (String name : HISTORICAL_RESOURCES) {
                Path target = historicalMigrations.resolve(name);
                if (!Files.exists(target)) {
                    try (var input = new ClassPathResource("db/migration/" + name).getInputStream()) {
                        Files.copy(input, target);
                    }
                }
            }
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot prepare the fixed V46 to V57 migration resources", failure);
        }
        FluentConfiguration configuration=Flyway.configure().dataSource(source)
                .locations("filesystem:" + historicalMigrations.toAbsolutePath());
        // 与生产 OnlineMigrationConfiguration 相同：CONCURRENTLY 不能被事务级迁移锁的快照阻塞。
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class).setTransactionalLock(false);
        return configuration;
    }
    private long company(JdbcTemplate jdbc) {
        long id=jdbc.queryForObject("SELECT nextval(pg_get_serial_sequence('bas_company','id'))",Long.class);
        String code="G"+Long.toString(id,36).toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("INSERT INTO bas_company(id,code,name,short_name) OVERRIDING SYSTEM VALUE VALUES (?,?,?,'FG migration') RETURNING id",Long.class,id,code,code);
    }
    private Fixture fixture(JdbcTemplate jdbc) {
        long company=company(jdbc);
        long warehouse=jdbc.queryForObject("INSERT INTO bas_warehouse(code,name,type) VALUES ('OLD-FG','Old FG','PRODUCT') RETURNING id",Long.class);
        long category=jdbc.queryForObject("INSERT INTO bas_category(name,level) VALUES ('Old FG',1) RETURNING id",Long.class);
        long group=jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES ('Old FG','M') RETURNING id",Long.class);
        long color=jdbc.queryForObject("INSERT INTO bas_color(code,name) VALUES ('FG','Old FG') RETURNING id",Long.class);
        long style=jdbc.queryForObject("INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id) VALUES ('OLD-FG','Old FG','FS','S1',?,2026,'AUTUMN','OWN',?) RETURNING id",Long.class,category,group);
        long sku=jdbc.queryForObject("INSERT INTO sty_style_sku(style_id,code,color_id,size_code) VALUES (?,'OLD-FG-M',?,'M') RETURNING id",Long.class,style,color);
        return new Fixture(company,warehouse,sku);
    }
    private long doc(JdbcTemplate jdbc,long company,long warehouse,String status) {
        return jdbc.queryForObject("INSERT INTO fg_doc(no,company_id,kind,doc_type,to_warehouse_id,doc_date,status) VALUES (?,?,'IN','OTHER_IN',?,current_date,?) RETURNING id",Long.class,UUID.randomUUID().toString().replace("-",""),company,warehouse,status);
    }
    private void event(JdbcTemplate jdbc,long doc,Fixture f,int delta,int balance) {
        jdbc.update("INSERT INTO fg_stock_log(fg_doc_id,change_type,doc_no,biz_date,warehouse_id,style_sku_id,change_qty,balance_qty) SELECT id,'OTHER_IN',no,doc_date,?,?,?,? FROM fg_doc WHERE id=?",f.warehouse(),f.sku(),delta,balance,doc);
    }
    private String snapshot(JdbcTemplate jdbc) { return jdbc.queryForObject("SELECT jsonb_agg(jsonb_build_object('id',id,'qty',change_qty,'balance',balance_qty) ORDER BY id)::text FROM fg_stock_log",String.class); }
    private void writeEvidence(String name,String value) { try { Path p=Path.of("target","a1-fg",name+".json");Files.createDirectories(p.getParent());Files.writeString(p,value); } catch(Exception failure) { throw new AssertionError(failure); } }
    private record Fixture(long company,long warehouse,long sku) { }
}
