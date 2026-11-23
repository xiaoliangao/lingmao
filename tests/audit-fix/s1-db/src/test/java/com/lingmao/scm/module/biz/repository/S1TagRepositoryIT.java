package com.lingmao.scm.module.biz.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.entity.Tag;
import com.lingmao.scm.module.biz.entity.TagLink;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 所有并发各用真实事务/数据库连接；不以 Mockito、串行循环或预制成功结果冒充锁验证。 */
@IntegrationTest
class S1TagRepositoryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired TagRepository tags;
    @Autowired DeploymentNamespace namespace;
    @Autowired JsonMapper json;

    @AfterEach
    void clearIdentity() { TestIdentity.clear(); }

    @Test
    void twentyDifferentOwnersCreateOneNormalizedActiveTagAndAllCommit() throws Exception {
        String name = unique("same");
        List<Long> owners = suppliers(20);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        record Outcome(long tagId, int version, int backend) { }
        List<Outcome> results = parallel(20, index -> transactions.execute(status -> {
            int current = active.incrementAndGet(); maximum.accumulateAndGet(current, Math::max);
            try {
                int backend = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
                TagRepository.Target target = lockSupplier(owners.get(index));
                String input = switch (index % 3) {
                    case 0 -> "# " + name.toUpperCase(java.util.Locale.ROOT);
                    case 1 -> "＃" + fullwidthAscii(name);
                    default -> name;
                };
                var saved = tags.replaceBindings(target, 0, List.of(input), Map.of(), List.of());
                return new Outcome(saved.after().links().getFirst().tag().id(), saved.after().version(), backend);
            } finally { active.decrementAndGet(); }
        }));
        assertThat(results).hasSize(20);
        assertThat(results.stream().map(Outcome::tagId).distinct()).hasSize(1);
        assertThat(results).extracting(Outcome::version).containsOnly(1);
        assertThat(results.stream().map(Outcome::backend).distinct().count()).isGreaterThanOrEqualTo(2);
        assertThat(maximum.get()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=? AND deleted_token=0",
                Integer.class, namespace.tenant(), name)).isEqualTo(1);
        long id = results.getFirst().tagId();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_link WHERE tenant_key=? AND tag_id=?",
                Integer.class, namespace.tenant(), id)).isEqualTo(20);
        evidence("twenty-same-name.json", Map.of("results", results, "maxConcurrentTransactions", maximum.get()));
    }

    @Test
    void reverseNameOrderAcrossOwnersUsesSameNameAndTagLockOrder() throws Exception {
        List<Long> owners = suppliers(20);
        String first = unique("first"); String second = unique("second");
        List<TagRepository.Snapshot> results = parallel(20, index -> transactions.execute(status -> {
            var names = index % 2 == 0 ? List.of(first, second) : List.of(second, first);
            return tags.replaceBindings(lockSupplier(owners.get(index)), 0, names, Map.of(), List.of()).after();
        }));
        assertThat(results).hasSize(20).allSatisfy(snapshot -> assertThat(snapshot.links()).hasSize(2));
        assertThat(results.stream().flatMap(snapshot -> snapshot.links().stream()).map(link -> link.tag().id()).distinct()).hasSize(2);
        for (int index = 0; index < results.size(); index++) {
            assertThat(results.get(index).links()).extracting(link -> link.tag().canonical())
                    .containsExactlyElementsOf(index % 2 == 0 ? List.of(first, second) : List.of(second, first));
        }
        evidence("reverse-name-order.json", results);
    }

    @Test
    void twoVersionZeroWritersToOneObjectProduceOneSuccessAndOneStateChanged() throws Exception {
        long owner = supplier(); String first = unique("cas"); String second = unique("cas");
        List<Integer> codes = parallel(2, index -> {
            try {
                transactions.executeWithoutResult(status -> tags.replaceBindings(lockSupplier(owner), 0,
                        List.of(index == 0 ? first : second), Map.of(), List.of()));
                return 0;
            } catch (BizException failure) { return failure.getCode(); }
        });
        assertThat(codes).containsExactlyInAnyOrder(0, 1002);
        var stored = tags.read(target(owner));
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.links()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical IN (?,?)",
                Integer.class, namespace.tenant(), first, second)).isEqualTo(1);
        evidence("same-object-cas.json", Map.of("codes", codes, "stored", stored));
    }

    @Test
    void rollbackRemovesRegistryNewTagLinksAndTheirFieldAudit() {
        long owner = supplier(); String name = unique("rollback");
        long before = auditCount("sup_supplier", owner);
        transactions.executeWithoutResult(status -> {
            tags.replaceBindings(lockSupplier(owner), 0, List.of(name), Map.of(), List.of());
            assertThat(tags.read(target(owner)).links()).hasSize(1);
            status.setRollbackOnly();
        });
        assertThat(tags.read(target(owner))).isEqualTo(new TagRepository.Snapshot(0, List.of()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=?",
                Integer.class, namespace.tenant(), name)).isZero();
        assertThat(auditCount("sup_supplier", owner)).isEqualTo(before);
    }

    @Test
    void archivedTagRetainsItsLinkOnReorderButReaddingAfterRemovalCreatesNewActiveIdentity() throws Exception {
        long owner = supplier(); String a = unique("archive"); String b = unique("other");
        var before = replace(owner, 0, List.of(a, b), Map.of(), List.of());
        TagLink original = before.links().getFirst();
        Tag archived = transactions.execute(status -> tags.archive(original.tag().id(), 0));
        assertThat(archived.archived()).isTrue();
        var updated = tags.read(target(owner));
        var ordered = replace(owner, updated.version(), List.of(b, a), versions(updated),
                List.of(original.tag().id(), before.links().getLast().tag().id()));
        assertThat(ordered.links().getLast().id()).isEqualTo(original.id());
        assertThat(ordered.links().getLast().tag().id()).isEqualTo(original.tag().id());
        assertThat(ordered.links().getLast().tag().archived()).isTrue();
        var removed = replace(owner, ordered.version(), List.of(b), versions(ordered), List.of(before.links().getLast().tag().id()));
        var readded = replace(owner, removed.version(), List.of(a, b), versions(removed), List.of(before.links().getLast().tag().id()));
        assertThat(readded.links().getFirst().tag().id()).isNotEqualTo(original.tag().id());
        assertThat(readded.links().getFirst().tag().archived()).isFalse();
        assertThat(tags.get(original.tag().id()).archived()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=?",
                Integer.class, namespace.tenant(), a)).isEqualTo(2);
        evidence("archive-retain-recreate.json", Map.of("original", original, "reordered", ordered, "readded", readded));
    }

    @Test
    void renamedActiveAndArchivedTagsWithSameCanonicalAreRetainedByExactIds() throws Exception {
        long owner = supplier(); String a = unique("collision"); String b = unique("rename");
        var before = replace(owner, 0, List.of(a, b), Map.of(), List.of());
        long archivedId = before.links().getFirst().tag().id();
        long activeId = before.links().getLast().tag().id();
        transactions.executeWithoutResult(status -> {
            tags.archive(archivedId, 0);
            tags.rename(activeId, 0, a);
        });
        var same = tags.read(target(owner));
        assertThat(same.links()).extracting(link -> link.tag().canonical()).containsExactly(a, a);
        var keepBoth = replace(owner, same.version(), List.of(a), versions(same), List.of(activeId, archivedId));
        assertThat(keepBoth.links()).extracting(link -> link.tag().id()).containsExactly(activeId, archivedId);
        assertThat(keepBoth.links()).extracting(TagLink::id)
                .containsExactly(before.links().getLast().id(), before.links().getFirst().id());
        var keepArchive = replace(owner, keepBoth.version(), List.of(a), versions(keepBoth), List.of(archivedId));
        assertThat(keepArchive.links()).extracting(link -> link.tag().id()).containsExactly(archivedId);
        var chooseActive = replace(owner, keepArchive.version(), List.of(a), versions(keepArchive), List.of());
        assertThat(chooseActive.links()).extracting(link -> link.tag().id()).containsExactly(activeId);
        assertThatThrownBy(() -> replace(owner, chooseActive.version(), List.of(a), versions(chooseActive), List.of(archivedId)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(400));
        evidence("same-canonical-explicit-retention.json", Map.of("before", before, "same", same, "both", keepBoth,
                "archiveOnly", keepArchive, "activeOnly", chooseActive));
    }

    @Test
    void globalRenameAndOldNamesReplaceActuallyContendThenRejectStaleForm() throws Exception {
        long owner = supplier(); String oldName = unique("old"); String newName = unique("new");
        var before = replace(owner, 0, List.of(oldName), Map.of(), List.of());
        long id = before.links().getFirst().tag().id();
        var renamed = new CountDownLatch(1); var release = new CountDownLatch(1); var writerStarted = new CountDownLatch(1);
        AtomicInteger writerBackend = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Tag> rename = executor.submit(() -> transactions.execute(status -> {
                Tag tag = tags.rename(id, 0, newName);
                renamed.countDown(); await(release); return tag;
            }));
            await(renamed);
            Future<Integer> replace = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        writerBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        writerStarted.countDown();
                        tags.replaceBindings(lockSupplier(owner), before.version(), List.of(oldName), versions(before), List.of(id));
                    });
                    return 0;
                } catch (BizException failure) { return failure.getCode(); }
            });
            try {
                await(writerStarted); assertBackendBlocked(writerBackend.get());
            } finally { release.countDown(); }
            assertThat(rename.get(10, TimeUnit.SECONDS).canonical()).isEqualTo(newName);
            assertThat(replace.get(10, TimeUnit.SECONDS)).isEqualTo(1002);
        } finally { release.countDown(); }
        var after = tags.read(target(owner));
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(after.links().getFirst().tag().canonical()).isEqualTo(newName);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag WHERE tenant_key=? AND canonical=? AND deleted_token=0",
                Integer.class, namespace.tenant(), oldName)).isZero();
        evidence("rename-vs-replace.json", Map.of("before", before, "after", after, "replaceCode", 1002));
    }

    @Test
    void namespaceAndCompositeForeignKeysRejectInvalidDeploymentWithoutInventingSecondLegalTenant() {
        long owner = supplier(); var saved = replace(owner, 0, List.of(unique("namespace")), Map.of(), List.of());
        long tagId = saved.links().getFirst().tag().id(); long object = saved.links().getFirst().objectRefId();
        assertSqlState("23503", () -> jdbc.update("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES ('invalid-second','x','x')"));
        assertSqlState("23503", () -> jdbc.update("""
                INSERT INTO biz_tag_object(tenant_key,object_type,object_id) VALUES ('invalid-second','SUPPLIER',?)
                """, owner));
        Throwable mixed = catchThrowable(() -> jdbc.update("""
                INSERT INTO biz_tag_link(tenant_key,object_ref_id,tag_id,seq) VALUES ('invalid-second',?,?,2)
                """, object, tagId));
        assertThat(sqlState(mixed)).isEqualTo("23503");
        assertThat(mixed).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_deployment_namespace", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conrelid IN ('biz_tag'::regclass,'biz_tag_object'::regclass,'biz_tag_link'::regclass)
                  AND confrelid='sys_deployment_namespace'::regclass AND contype='f'
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conrelid='biz_tag_link'::regclass AND contype='f'
                  AND cardinality(conkey)=2 AND confrelid IN ('biz_tag'::regclass,'biz_tag_object'::regclass)
                """, Integer.class)).isEqualTo(2);
        assertThat(TagRepository.Target.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("type", "id", "companyId");
        assertThat(tags.findByIds(List.of(tagId, tagId))).extracting(Tag::id).containsExactly(tagId);
        assertThatThrownBy(() -> tags.findByIds(List.of(tagId, Long.MAX_VALUE)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(404));
    }

    @Test
    void parentDeleteAfterAttachWaitsThenCleansLinksAndRegistryButRetainsLibraryAndAudit() throws Exception {
        long owner = supplier(); var attached = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicInteger deleteBackend = new AtomicInteger(); var deleting = new CountDownLatch(1);
        long tagId;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Long> attach = executor.submit(() -> transactions.execute(status -> {
                var result = tags.replaceBindings(lockSupplier(owner), 0, List.of(unique("delete")), Map.of(), List.of());
                attached.countDown(); await(release); return result.after().links().getFirst().tag().id();
            }));
            await(attached);
            Future<Integer> delete = executor.submit(() -> transactions.execute(status -> {
                deleteBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                deleting.countDown(); return jdbc.update("DELETE FROM sup_supplier WHERE id=?", owner);
            }));
            try { await(deleting); assertBackendBlocked(deleteBackend.get()); }
            finally { release.countDown(); }
            tagId = attach.get(10, TimeUnit.SECONDS);
            assertThat(delete.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
        assertNoTagOrphans();
        assertThat(tags.get(tagId)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE object_type='SUPPLIER' AND object_id=?",
                Integer.class, owner)).isZero();
        var rows = jdbc.queryForList("""
                SELECT table_name,action,root_table,root_id::text,diff::text AS diff FROM sys_data_log
                WHERE tenant_key=? AND root_table='sup_supplier' AND root_id=? AND table_name LIKE 'biz_tag%' ORDER BY id
                """, namespace.tenant(), owner);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("table_name")).isEqualTo("biz_tag_link");
            assertThat(row.get("action")).isEqualTo("DELETE");
        });
        evidence("attach-then-parent-delete.json", rows);
    }

    @Test
    void parentDeleteBeforeAttachReturnsNotFoundAndCannotLeaveAnOrphan() throws Exception {
        long owner = supplier(); var deleted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var starting = new CountDownLatch(1); AtomicInteger backend = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> delete = executor.submit(() -> transactions.execute(status -> {
                int count = jdbc.update("DELETE FROM sup_supplier WHERE id=?", owner);
                deleted.countDown(); await(release); return count;
            }));
            await(deleted);
            Future<Integer> attach = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); starting.countDown();
                        tags.replaceBindings(lockSupplier(owner), 0, List.of(unique("gone")), Map.of(), List.of());
                    });
                    return 0;
                } catch (BizException failure) { return failure.getCode(); }
            });
            try { await(starting); assertBackendBlocked(backend.get()); } finally { release.countDown(); }
            assertThat(delete.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(attach.get(10, TimeUnit.SECONDS)).isEqualTo(404);
        } finally { release.countDown(); }
        assertNoTagOrphans();
    }

    @Test
    void triggerShareLockBlocksNonKeyCompanyChangeDuringFirstRawAssociation() throws Exception {
        long firstCompany = company(); long secondCompany = company(); long owner = mrp(firstCompany);
        Tag tag = rawTag(unique("company"));
        var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var updating = new CountDownLatch(1); AtomicInteger backend = new AtomicInteger();
        long registry;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Long> attach = executor.submit(() -> transactions.execute(status -> {
                // 故意不预锁父头：只有 V58 的 FOR SHARE 能阻止非键 company_id UPDATE。
                long id = jdbc.queryForObject("""
                        INSERT INTO biz_tag_object(tenant_key,object_type,object_id,company_id)
                        VALUES (?,'MRP_CALC',?,?) RETURNING id
                        """, Long.class, namespace.tenant(), owner, firstCompany);
                jdbc.update("INSERT INTO biz_tag_link(tenant_key,object_ref_id,tag_id,seq) VALUES (?,?,?,1)", namespace.tenant(), id, tag.id());
                inserted.countDown(); await(release); return id;
            }));
            await(inserted);
            Future<Integer> update = executor.submit(() -> transactions.execute(status -> {
                backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); updating.countDown();
                return jdbc.update("UPDATE mrp_calc SET company_id=? WHERE id=?", secondCompany, owner);
            }));
            try { await(updating); assertBackendBlocked(backend.get()); } finally { release.countDown(); }
            registry = attach.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE id=?", Long.class, registry)).isEqualTo(secondCompany);
        assertThat(jdbc.queryForObject("SELECT version FROM biz_tag_object WHERE id=?", Integer.class, registry)).isZero();
        assertThat(tags.read(new TagRepository.Target(TagObjectType.MRP_CALC, owner, secondCompany)).links()).hasSize(1);
        evidence("parent-company-first-association.json", jdbc.queryForMap("""
                SELECT o.company_id::text AS registry_company,p.company_id::text AS parent_company,o.version
                FROM biz_tag_object o JOIN mrp_calc p ON p.id=o.object_id WHERE o.id=?
                """, registry));
    }

    @Test
    void companyChangeBeforeNormalOwnerLockIsReadAsNewCompanyAndRawReverseWriterFailsFast() throws Exception {
        long firstCompany = company(); long secondCompany = company(); long owner = mrp(firstCompany);
        var changed = new CountDownLatch(1); var release = new CountDownLatch(1);
        var starting = new CountDownLatch(1); AtomicInteger backend = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> update = executor.submit(() -> transactions.execute(status -> {
                int count = jdbc.update("UPDATE mrp_calc SET company_id=? WHERE id=?", secondCompany, owner);
                changed.countDown(); await(release); return count;
            }));
            await(changed);
            // 原始 SQL 未遵守 owner→registry，不能持有子行反过来长等父头。
            assertSqlState("55P03", () -> jdbc.update("""
                    INSERT INTO biz_tag_object(tenant_key,object_type,object_id,company_id) VALUES (?,'MRP_CALC',?,?)
                    """, namespace.tenant(), owner, firstCompany));
            Future<TagRepository.Change> attach = executor.submit(() -> transactions.execute(status -> {
                backend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)); starting.countDown();
                long current = jdbc.queryForObject("SELECT company_id FROM mrp_calc WHERE id=? FOR UPDATE", Long.class, owner);
                return tags.replaceBindings(new TagRepository.Target(TagObjectType.MRP_CALC, owner, current), 0,
                        List.of(unique("company-after")), Map.of(), List.of());
            }));
            try { await(starting); assertBackendBlocked(backend.get()); } finally { release.countDown(); }
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(attach.get(10, TimeUnit.SECONDS).after().version()).isEqualTo(1);
        } finally { release.countDown(); }
        var target = new TagRepository.Target(TagObjectType.MRP_CALC, owner, secondCompany);
        assertThat(tags.read(target).links()).hasSize(1);
        assertThatThrownBy(() -> tags.read(new TagRepository.Target(TagObjectType.MRP_CALC, owner, firstCompany)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(1002));
    }

    @Test
    void directReparentArchivedInsertIdMutationAndTruncateCannotBypassIntegrity() {
        long firstOwner = supplier(); long secondOwner = supplier();
        var first = replace(firstOwner, 0, List.of(unique("guard")), Map.of(), List.of());
        var second = replace(secondOwner, 0, List.of(unique("guard")), Map.of(), List.of());
        TagLink link = first.links().getFirst(); long secondRef = second.links().getFirst().objectRefId();
        assertSqlState("55000", () -> jdbc.update("UPDATE biz_tag_link SET object_ref_id=? WHERE id=?", secondRef, link.id()));
        assertSqlState("55000", () -> jdbc.update("UPDATE biz_tag_object SET object_id=? WHERE id=?", secondOwner, link.objectRefId()));
        // GENERATED ALWAYS 身份列先拒绝显式值(428C9)；DEFAULT 是合法 identity 更新，真正进入父 ID 守卫。
        assertSqlState("23503", () -> jdbc.update("UPDATE sup_supplier SET id=DEFAULT WHERE id=?", firstOwner));
        transactions.executeWithoutResult(status -> tags.archive(link.tag().id(), 0));
        assertSqlState("23514", () -> jdbc.update("INSERT INTO biz_tag_link(tenant_key,object_ref_id,tag_id,seq) VALUES (?,?,?,2)",
                namespace.tenant(), secondRef, link.tag().id()));
        assertSqlState("55000", () -> jdbc.update("DELETE FROM biz_tag WHERE id=?", link.tag().id()));
        assertSqlState("55000", () -> jdbc.update("UPDATE biz_tag SET deleted_token=0,version=version+1 WHERE id=?", link.tag().id()));
        for (String table : List.of("biz_tag", "biz_tag_object", "biz_tag_link")) {
            assertSqlState("55000", () -> transactions.executeWithoutResult(status -> {
                status.setRollbackOnly(); jdbc.execute("TRUNCATE " + table + " CASCADE");
            }));
        }
        assertSqlState("23503", () -> transactions.executeWithoutResult(status -> {
            status.setRollbackOnly(); jdbc.execute("TRUNCATE sup_supplier CASCADE");
        }));
        assertNoTagOrphans();
    }

    @Test
    void fieldDiffUsesExactOldAndNewSeqWithStringIdsAndRollbackLeavesNoDeleteAudit() throws Exception {
        long owner = supplier(); String a = unique("diff"); String b = unique("diff");
        var before = replace(owner, 0, List.of(a, b), Map.of(), List.of());
        long link = before.links().getFirst().id();
        var after = replace(owner, before.version(), List.of(b, a), versions(before),
                before.links().stream().map(value -> value.tag().id()).toList());
        assertThat(after.links().getLast().id()).isEqualTo(link);
        String diff = jdbc.queryForObject("""
                SELECT diff::text FROM sys_data_log WHERE tenant_key=? AND table_name='biz_tag_link'
                  AND row_id=? AND action='UPDATE' ORDER BY id DESC LIMIT 1
                """, String.class, namespace.tenant(), link);
        var node = json.readTree(diff);
        assertThat(node.path("seq").path("old").asInt()).isEqualTo(1);
        assertThat(node.path("seq").path("new").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT jsonb_typeof(diff->'id'->'new') FROM sys_data_log
                WHERE tenant_key=? AND table_name='biz_tag_link' AND row_id=? AND action='INSERT'
                """, String.class, namespace.tenant(), link)).isEqualTo("string");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sys_data_log WHERE tenant_key=? AND root_table='sup_supplier' AND root_id=?
                  AND table_name LIKE 'biz_tag%' AND action='REPLACE'
                """, Integer.class, namespace.tenant(), owner)).isZero();
        long auditBefore = auditCount("sup_supplier", owner);
        transactions.executeWithoutResult(status -> { jdbc.update("DELETE FROM sup_supplier WHERE id=?", owner); status.setRollbackOnly(); });
        assertThat(tags.read(target(owner))).isEqualTo(after);
        assertThat(auditCount("sup_supplier", owner)).isEqualTo(auditBefore);
        long big = 9_007_199_254_741_017L;
        String bigName = unique("long-id");
        jdbc.update("""
                INSERT INTO biz_tag(id,tenant_key,name,canonical) OVERRIDING SYSTEM VALUE VALUES (?,?,?,?)
                """, big, namespace.tenant(), bigName, bigName);
        assertThat(jdbc.queryForObject("SELECT diff->'id'->>'new' FROM sys_data_log WHERE table_name='biz_tag' AND row_id=?",
                String.class, big)).isEqualTo(Long.toString(big));
        evidence("tag-field-diff.json", Map.of("before", before, "after", after, "actualDiff", node,
                "longId", jdbc.queryForObject("SELECT diff::text FROM sys_data_log WHERE table_name='biz_tag' AND row_id=?", String.class, big)));
    }

    @Test
    void unicodeDatabaseBoundsControlsJoinersAndExistingHashNamesMatchJavaNormalization() throws Exception {
        long owner = supplier();
        String emoji = "😀".repeat(64);
        String expanded = "İ".repeat(64);
        String joined = "👩\u200D💻" + unique("join");
        String hash = "#蓝" + unique("hash");
        var before = replace(owner, 0, List.of(emoji, expanded, joined, "#" + hash), Map.of(), List.of());
        assertThat(before.links()).extracting(link -> link.tag().name()).containsExactly(emoji, expanded, joined, hash);
        long expandedId = before.links().get(1).tag().id();
        assertThat(jdbc.queryForObject("SELECT char_length(name) FROM biz_tag WHERE id=?", Integer.class, expandedId)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT char_length(canonical) FROM biz_tag WHERE id=?", Integer.class, expandedId)).isEqualTo(128);
        assertThat(jdbc.queryForObject("SELECT char_length(name) FROM biz_tag WHERE id=?", Integer.class,
                before.links().getFirst().tag().id())).isEqualTo(64);
        var after = replace(owner, before.version(), before.links().stream().map(link -> "#" + link.tag().name()).toList(),
                versions(before), before.links().stream().map(link -> link.tag().id()).toList());
        assertThat(after.links()).isEqualTo(before.links());
        for (String invalid : List.of("Cc\u0085", "bidi\u202E", "bidi\u2066")) {
            assertSqlState("23514", () -> jdbc.update("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES (?,?,?)",
                    namespace.tenant(), invalid, invalid));
        }
        evidence("unicode-database-roundtrip.json", Map.of("before", before, "after", after,
                "displayCodePoints", 64, "expandedCanonicalCodePoints", 128));
    }

    private TagRepository.Snapshot replace(long owner, int version, List<String> names, Map<Long, Integer> versions, List<Long> retained) {
        return transactions.execute(status -> tags.replaceBindings(lockSupplier(owner), version, names, versions, retained).after());
    }
    private TagRepository.Target target(long owner) { return new TagRepository.Target(TagObjectType.SUPPLIER, owner, null); }
    private TagRepository.Target lockSupplier(long owner) {
        List<Long> rows = jdbc.queryForList("SELECT id FROM sup_supplier WHERE id=? FOR UPDATE", Long.class, owner);
        if (rows.isEmpty()) throw new BizException(404, "对象不存在");
        return target(owner);
    }
    private long supplier() {
        String code = unique("TS");
        return jdbc.queryForObject("INSERT INTO sup_supplier(code,name,short_name) VALUES (?,?,?) RETURNING id", Long.class, code, code, code);
    }
    private List<Long> suppliers(int count) { var result = new ArrayList<Long>(); for (int i = 0; i < count; i++) result.add(supplier()); return result; }
    private long company() {
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(java.util.Locale.ROOT);
        return jdbc.queryForObject("INSERT INTO bas_company(code,name,short_name) VALUES (?,'S1 标签主体','S1') RETURNING id", Long.class, code);
    }
    private long mrp(long company) {
        return jdbc.queryForObject("INSERT INTO mrp_calc(no,company_id) VALUES (?,?) RETURNING id", Long.class, unique("TAGM"), company);
    }
    private Tag rawTag(String name) {
        long id = jdbc.queryForObject("INSERT INTO biz_tag(tenant_key,name,canonical) VALUES (?,?,?) RETURNING id",
                Long.class, namespace.tenant(), name, name);
        return tags.get(id);
    }
    private long auditCount(String parent, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM sys_data_log WHERE tenant_key=? AND root_table=? AND root_id=? AND table_name LIKE 'biz_tag%'",
                Long.class, namespace.tenant(), parent, id);
    }
    private static Map<Long, Integer> versions(TagRepository.Snapshot snapshot) {
        var result = new HashMap<Long, Integer>(); snapshot.links().forEach(link -> result.put(link.tag().id(), link.tag().version())); return result;
    }
    private static String unique(String prefix) { return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14); }
    private static String fullwidthAscii(String input) {
        var result = new StringBuilder();
        input.codePoints().forEach(codePoint -> result.appendCodePoint(codePoint >= 0x21 && codePoint <= 0x7e ? codePoint + 0xfee0 : codePoint));
        return result.toString();
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("concurrency checkpoint timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static <T> List<T> parallel(int count, IntFunction<T> operation) throws Exception {
        var gate = new CyclicBarrier(count);
        try (var executor = Executors.newFixedThreadPool(count)) {
            var futures = new ArrayList<Future<T>>();
            for (int index = 0; index < count; index++) {
                int current = index;
                futures.add(executor.submit(() -> { gate.await(10, TimeUnit.SECONDS); return operation.apply(current); }));
            }
            var results = new ArrayList<T>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }
    private void assertBackendBlocked(int backend) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?", Boolean.class, backend);
            if (Boolean.TRUE.equals(waiting)) return;
            Thread.sleep(10);
        }
        throw new AssertionError("expected a real PostgreSQL lock wait for backend " + backend);
    }
    private void assertNoTagOrphans() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM biz_tag_link l
                LEFT JOIN biz_tag_object o ON o.tenant_key=l.tenant_key AND o.id=l.object_ref_id
                LEFT JOIN biz_tag t ON t.tenant_key=l.tenant_key AND t.id=l.tag_id WHERE o.id IS NULL OR t.id IS NULL
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM biz_tag_object o LEFT JOIN sup_supplier p ON p.id=o.object_id
                WHERE o.object_type='SUPPLIER' AND p.id IS NULL
                """, Integer.class)).isZero();
    }
    private static void assertSqlState(String state, Runnable statement) {
        Throwable failure = catchThrowable(statement::run);
        assertThat(failure).isNotNull(); assertThat(sqlState(failure)).isEqualTo(state);
    }
    private static String sqlState(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) return sql.getSQLState();
        }
        return null;
    }
    private void evidence(String name, Object value) throws Exception {
        Path path = Path.of("target/a1-tags", name); Files.createDirectories(path.getParent()); Files.writeString(path, json.writeValueAsString(value));
    }
}
