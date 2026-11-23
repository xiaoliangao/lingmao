package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.IsolatedRoleMigration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 标签增量本身的真实目录门禁；全库 SchemaConventionsIT 仍由主控集成后跑，不能用此替代。 */
@IntegrationTest
class S1TagSchemaIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Value("${spring.datasource.password:}") String databasePassword;

    @Test
    void everyNewForeignKeyHasItsActualLeadingIndexAndNoCascade() {
        assertThat(jdbc.queryForList("""
                SELECT c.conrelid::regclass::text || '.' || c.conname::text
                FROM pg_constraint c
                WHERE c.contype='f' AND c.conrelid IN ('biz_tag'::regclass,'biz_tag_object'::regclass,'biz_tag_link'::regclass)
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_index i WHERE i.indrelid=c.conrelid
                      AND cardinality(c.conkey)<=i.indnkeyatts AND i.indpred IS NULL
                      AND (SELECT array_agg(i.indkey[k] ORDER BY k)
                           FROM generate_series(0,cardinality(c.conkey)-1) k)=c.conkey)
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT conname::text FROM pg_constraint WHERE contype='f'
                  AND conrelid IN ('biz_tag'::regclass,'biz_tag_object'::regclass,'biz_tag_link'::regclass)
                  AND (confdeltype NOT IN ('a','r') OR confupdtype NOT IN ('a','r'))
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname IN ('biz_tag','biz_tag_object','biz_tag_link') AND obj_description(oid) IS NOT NULL",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT condeferrable AND condeferred FROM pg_constraint WHERE conname='uk_biz_tag_link_seq'", Boolean.class)).isTrue();
    }

    @Test
    void allThirteenRealParentsHaveDeletionAndTruncateGuardsAndAllNineCompanyOwnersSynchronize() {
        List<String> parents = List.of("sty_style", "bas_material", "sup_supplier", "tp_tech_pack", "ord_order", "mrp_calc",
                "pur_order", "inv_doc", "inv_count", "prd_order", "prd_receipt", "fg_doc", "dev_sample");
        for (String parent : parents) {
            assertThat(jdbc.queryForList("""
                    SELECT tgname::text FROM pg_trigger WHERE tgrelid=CAST(? AS regclass)
                      AND tgname IN ('tag_parent_guard','tag_parent_truncate') AND NOT tgisinternal AND tgenabled='O'
                    ORDER BY tgname
                    """, String.class, parent)).containsExactly("tag_parent_guard", "tag_parent_truncate");
        }
        assertThat(jdbc.queryForList("""
                SELECT tgrelid::regclass::text FROM pg_trigger WHERE tgname='tag_parent_company' AND NOT tgisinternal ORDER BY 1
                """, String.class)).containsExactlyInAnyOrder("ord_order", "mrp_calc", "pur_order", "inv_doc", "inv_count", "prd_order", "prd_receipt", "fg_doc", "dev_sample");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_deployment_namespace", Integer.class)).isEqualTo(1);
    }

    @Test
    void visibleCatalogAndOnlyExistingApprovedBusinessRolesReceiveFrozenPermissions() throws Exception {
        try (var migration = IsolatedRoleMigration.migrate(dataSource, databasePassword, "58")) {
            JdbcTemplate jdbc = migration.jdbc();
            var menu = jdbc.queryForMap("SELECT path,component,icon,sort,visible,admin_only FROM sys_menu WHERE perms='biz:tag:list' AND NOT deleted");
            assertThat(menu).containsEntry("path", "/tags").containsEntry("component", "biz/tag/index").containsEntry("icon", "PriceTag")
                    .containsEntry("sort", 60).containsEntry("visible", true).containsEntry("admin_only", false);
            List<String> readers = jdbc.queryForList("""
                    SELECT DISTINCT r.code FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
                    WHERE NOT r.deleted AND NOT m.deleted AND m.perms='biz:tag:list' ORDER BY r.code
                    """, String.class);
            assertThat(readers).contains("dev", "dev_lead", "sales", "merchandiser", "buyer", "warehouse", "finance", "manager")
                    .allMatch(code -> List.of("dev", "dev_lead", "sales", "merchandiser", "buyer", "warehouse", "finance", "manager", "prod_manager").contains(code));
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT r.code FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
                    WHERE NOT r.deleted AND NOT m.deleted AND m.perms='biz:tag:manage'
                    """, String.class)).containsExactly("manager");
            migration.assertSentinelsUnchanged();
            migration.flyway().validate();
            assertThat(migration.flyway().migrate().migrationsExecuted).isZero();
        }
    }
}
