package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.IsolatedRoleMigration;
import com.lingmao.scm.security.DataScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 新领域真实目录/FK/角色门禁；不豁免全库 SchemaConventionsIT。 */
@IntegrationTest
class S1SampleSchemaIT extends SampleTestSupport {
    @Autowired Flyway flyway;
    @Autowired DataSource dataSource;
    @Value("${spring.datasource.password:}") String databasePassword;

    @Test
    void allEightTablesHaveNamespaceAndCompanyForeignKeysAndEveryFkHasAnUnconditionalLeadingIndex() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_class WHERE relname LIKE 'dev_sample%' AND relkind='r' AND obj_description(oid) IS NOT NULL", Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForList("""
                SELECT c.conrelid::regclass::text || '.' || c.conname::text FROM pg_constraint c
                JOIN pg_class r ON r.oid=c.conrelid
                WHERE c.contype='f' AND (r.relname LIKE 'dev_sample%' OR c.conname='tp_tech_pack_sample_id_fkey')
                  AND NOT EXISTS(SELECT 1 FROM pg_index i WHERE i.indrelid=c.conrelid
                    AND cardinality(c.conkey)<=i.indnkeyatts AND i.indpred IS NULL
                    AND (SELECT array_agg(i.indkey[k] ORDER BY k) FROM generate_series(0,cardinality(c.conkey)-1) k)=c.conkey)
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid
                WHERE r.relname LIKE 'dev_sample%' AND c.contype='f' AND c.confrelid='sys_deployment_namespace'::regclass
                """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid
                WHERE r.relname LIKE 'dev_sample%' AND c.contype='f' AND c.confrelid='bas_company'::regclass
                """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForList("""
                SELECT c.conname::text FROM pg_constraint c JOIN pg_class r ON r.oid=c.conrelid
                WHERE r.relname LIKE 'dev_sample%' AND c.contype='f'
                  AND (c.confdeltype NOT IN ('a','r') OR c.confupdtype NOT IN ('a','r'))
                """, String.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT condeferrable AND condeferred FROM pg_constraint WHERE conname='fk_dev_sample_order_owner'", Boolean.class)).isTrue();
        flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void secondNamespaceAndMixedCompanyOrSizeGroupAreRejectedWithoutInventingASecondTenant() {
        Fixture f = fixture(); Created made = draft(f);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_deployment_namespace", Long.class)).isEqualTo(1);
        rejected("23503", () -> jdbc.update("""
                INSERT INTO dev_sample(tenant_key,company_id,code,name,brand,category_id,year,season,source,size_group_id)
                VALUES('illegal-second-namespace',?,?,'越命名空间','S1',?,2026,'SPRING','OWN',?)
                """, f.company(), unique("YY"), f.category(), f.sizeGroup()));
        rejected("23503", () -> jdbc.update("""
                INSERT INTO dev_sample_order(tenant_key,company_id,no,sample_id,sample_version,sample_type,need_date,qty,size_group_id,reason)
                VALUES(?,?,?,?,2,'REVISE',current_date,1,?,'混主体')
                """, namespace.tenant(), f.otherCompany(), unique("DY"), made.sampleId(), f.sizeGroup()));
        long otherGroup = jdbc.queryForObject("INSERT INTO bas_size_group(name,base_size) VALUES(?,'M') RETURNING id", Long.class, unique("other-group"));
        jdbc.update("INSERT INTO bas_size_group_item(size_group_id,seq,size_code) VALUES(?,1,'M')", otherGroup);
        rejected("23503", () -> transactions.executeWithoutResult(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, 0);
            jdbc.update("DELETE FROM dev_sample_order_size WHERE sample_order_id=?", made.orderId());
            jdbc.update("INSERT INTO dev_sample_order_size(tenant_key,company_id,sample_order_id,size_group_id,seq,size_code) VALUES(?,?,?,?,1,'M')",
                    namespace.tenant(), f.company(), made.orderId(), otherGroup);
        }));
        assertThat(jdbc.queryForObject("SELECT size_group_id FROM dev_sample_order_size WHERE sample_order_id=?", Long.class, made.orderId())).isEqualTo(f.sizeGroup());
    }

    @Test
    void stableNodeDictionaryAndVisibleMenuGrantOnlyFrozenExistingTemplates() throws Exception {
        try (var migration = IsolatedRoleMigration.migrate(dataSource, databasePassword, "59")) {
            JdbcTemplate jdbc = migration.jdbc();
            var menu = jdbc.queryForMap("SELECT path,component,icon,sort,visible FROM sys_menu WHERE perms='dev:sample:list' AND NOT deleted");
            assertThat(menu).containsEntry("path", "/samples").containsEntry("component", "dev/sample/index")
                    .containsEntry("icon", "EditPen").containsEntry("sort", 20).containsEntry("visible", true);
            assertThat(jdbc.queryForList("SELECT value FROM sys_dict_data WHERE type_code='sample_node' AND NOT deleted AND status=1 ORDER BY sort,id", String.class))
                    .containsExactly("RECEIVE", "PATTERN", "CUT", "SEW", "SEND");
            assertThat(jdbc.queryForObject("SELECT builtin FROM sys_dict_data WHERE type_code='sample_node' AND value='SEND' AND NOT deleted", Boolean.class)).isTrue();
            for (String role : List.of("dev", "dev_lead")) assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
                    WHERE r.code=? AND NOT r.deleted AND NOT m.deleted AND m.perms LIKE 'dev:sample:%'
                    """, Long.class, role)).isEqualTo(7);
            assertThat(jdbc.queryForList("""
                    SELECT m.perms FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
                    WHERE r.code='manager' AND NOT r.deleted AND NOT m.deleted AND m.perms LIKE 'dev:sample:%' ORDER BY m.perms
                    """, String.class)).containsExactly("dev:sample:list", "dev:sample:review");
            assertThat(jdbc.queryForList("""
                    SELECT DISTINCT r.code FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.id JOIN sys_menu m ON m.id=rm.menu_id
                    WHERE NOT r.deleted AND NOT m.deleted AND m.perms LIKE 'dev:sample:%'
                    """, String.class)).containsExactlyInAnyOrder("dev", "dev_lead", "manager");
            assertThat(jdbc.queryForList("SELECT tgname::text FROM pg_trigger WHERE tgrelid='dev_sample'::regclass AND tgname LIKE 'tag_%' ORDER BY tgname", String.class))
                    .contains("tag_parent_guard", "tag_parent_truncate", "tag_parent_company", "tag_sample_tombstone");
            migration.assertSentinelsUnchanged();
            migration.flyway().validate();
            assertThat(migration.flyway().migrate().migrationsExecuted).isZero();
        }
    }
}
