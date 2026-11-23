package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.TestIdentity;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** 资料包 owner/引用/标签归属的数据库验证；完整建款、SKU、BOM内容复制和现价校验由 s2 Service 链执行。 */
@IntegrationTest
class S1SampleOwnerIT extends SampleTestSupport {
    @Autowired TechPackMapper packs;
    @Autowired TagRepository tags;
    @Autowired SqlSessionFactory sessions;

    @Test
    void adoptingActualSamplePacksKeepsProvenanceAndMovesTagRegistryToStyleOwner() throws Exception {
        Fixture f = fixture(); Created made = passed(f);
        long pack = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            return pack(made.sampleId(), 1, 0, "DRAFT", null);
        });
        var before = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 101);
            return tags.replaceBindings(new TagRepository.Target(TagObjectType.TECH_PACK, pack, f.company()),
                    0, List.of(unique("tp-tag")), Map.of(), List.of()).after();
        });
        long style = transactions.execute(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            long result = fixtureStyle(f, unique("FS"));
            var lockedPacks = packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 50_001);
            assertThat(lockedPacks).hasSize(1); assertThat(lockedPacks.getFirst().companyId()).isEqualTo(f.company());
            assertThat(packs.adoptSamplePacks(namespace.tenant(), f.company(), made.sampleId(), result, f.creator())).isEqualTo(lockedPacks.size());
            lock.sample().setStatus(SampleStatus.CONVERTED); lock.sample().setConvertedStyleId(result);
            lock.sample().setConvertBy(f.creator()); lock.sample().setConvertTime(OffsetDateTime.now(clock));
            samples.updateSample(lock, lock.sample()); return result;
        });
        var identity = packs.ownerIdentity(pack);
        assertThat(identity.styleId()).isEqualTo(style); assertThat(identity.sampleId()).isEqualTo(made.sampleId());
        assertThat(identity.companyId()).isNull(); assertThat(identity.versionLock()).isEqualTo(1);
        assertThat(samples.convertedSource(style).getId()).isEqualTo(made.sampleId());
        assertThat(samples.convertedSource(style).getCompanyId()).isEqualTo(f.company());
        var after = tags.read(new TagRepository.Target(TagObjectType.TECH_PACK, pack, null));
        assertThat(after.version()).isEqualTo(before.version()); assertThat(after.links()).isEqualTo(before.links());
        assertThat(jdbc.queryForObject("SELECT company_id IS NULL FROM biz_tag_object WHERE object_type='TECH_PACK' AND object_id=?", Boolean.class, pack)).isTrue();
        var diff = jdbc.queryForMap("""
                SELECT root_table,root_id::text AS root_id,action,diff->'company_id'->>'old' AS old_company,
                    jsonb_typeof(diff->'company_id'->'new')='null' AS new_company_is_null,
                    jsonb_exists(diff,'version') AS content_version_changed
                FROM sys_data_log WHERE table_name='biz_tag_object' AND root_table='tp_tech_pack' AND root_id=?
                    AND action='UPDATE' AND jsonb_exists(diff,'company_id')
                """, pack);
        assertThat(diff).containsEntry("root_table", "tp_tech_pack").containsEntry("root_id", Long.toString(pack))
                .containsEntry("action", "UPDATE").containsEntry("old_company", Long.toString(f.company()))
                .containsEntry("new_company_is_null", true).containsEntry("content_version_changed", false);
        rejected("23503", () -> jdbc.update("DELETE FROM sty_style WHERE id=?", style));
        evidence("sample-pack-adoption-tags.json", Map.of("ownerAfter", identity, "tagsBefore", before, "tagsAfter", after, "companyDiff", diff));
    }

    @Test
    void adoptionFailureRollsBackNewStylePackOwnerVersionAndTagCompanyTogether() {
        Fixture f = fixture(); Created made = passed(f); String styleCode = unique("FS");
        long pack = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            return pack(made.sampleId(), 1, 0, "DRAFT", null);
        });
        transactions.executeWithoutResult(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 101);
            tags.replaceBindings(new TagRepository.Target(TagObjectType.TECH_PACK, pack, f.company()), 0,
                    List.of(unique("rollback-tag")), Map.of(), List.of());
        });
        transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            long style = fixtureStyle(f, styleCode);
            var locked = packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 50_001);
            assertThat(packs.adoptSamplePacks(namespace.tenant(), f.company(), made.sampleId(), style, f.creator())).isEqualTo(locked.size());
            lock.sample().setStatus(SampleStatus.CONVERTED); lock.sample().setConvertedStyleId(style);
            lock.sample().setConvertBy(f.creator()); lock.sample().setConvertTime(OffsetDateTime.now(clock));
            samples.updateSample(lock, lock.sample()); status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE code=?", Long.class, styleCode)).isZero();
        assertThat(packs.ownerIdentity(pack).styleId()).isNull(); assertThat(packs.ownerIdentity(pack).versionLock()).isZero();
        assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE object_type='TECH_PACK' AND object_id=?", Long.class, pack)).isEqualTo(f.company());
        assertThat(snapshot(f, made.sampleId()).sample().getStatus()).isEqualTo(SampleStatus.PASSED);
        assertThat(snapshot(f, made.sampleId()).sample().getVersion()).isEqualTo(3);
    }

    @Test
    void actualOwnerConstraintRejectsNoOwnerSampleProductionAndPartialConversion() {
        Fixture f = fixture(); Created made = passed(f);
        rejected("23514", () -> jdbc.update("INSERT INTO tp_tech_pack(stage,version) VALUES('DEV',1)"));
        rejected("23514", () -> jdbc.update("INSERT INTO tp_tech_pack(sample_id,stage,version) VALUES(?,'PROD',1)", made.sampleId()));
        long pack = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            return pack(made.sampleId(), 1, 0, "DRAFT", null);
        });
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            long style = fixtureStyle(f, unique("FS"));
            lock.sample().setStatus(SampleStatus.CONVERTED); lock.sample().setConvertedStyleId(style);
            lock.sample().setConvertTime(OffsetDateTime.now(clock)); lock.sample().setConvertBy(f.creator());
            samples.updateSample(lock, lock.sample()); // 故意漏掉真实移交，必须在 commit 被拒。
        }));
        assertThat(packs.ownerIdentity(pack).styleId()).isNull();
        rejected("23514", () -> transactions.executeWithoutResult(status -> {
            var lock = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            lock.sample().setCompanyId(f.otherCompany()); samples.updateSample(lock, lock.sample());
        }));
    }

    @Test
    void lightLockAndVersionHeadExecuteWithoutSelectingLargeOwnerSnapshotsAndMaxVersionRejectsWholeAdoption() throws Exception {
        Fixture f = fixture(); Created made = passed(f);
        // 故障注入：持久化超过正常显示字段长度的合法JSON快照，验证批量锁不把大字段传回JVM；不冒充可发布BOM内容。
        String huge = "{\"schemaVersion\":1,\"owner\":{\"name\":\"" + "x".repeat(131_072) + "\"},\"sizes\":[],\"colors\":[]}";
        transactions.executeWithoutResult(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            for (int version = 1; version <= 64; version++) pack(made.sampleId(), version,
                    version == 64 ? Integer.MAX_VALUE : 0, "VOIDED", huge);
        });
        long bytes = jdbc.queryForObject("SELECT sum(octet_length(owner_snapshot::text)) FROM tp_tech_pack WHERE sample_id=?", Long.class, made.sampleId());
        assertThat(bytes).isGreaterThan(8_000_000);
        Map<String, Object> values = new HashMap<>(); values.put("tenant", namespace.tenant()); values.put("companyId", f.company());
        values.put("sampleId", made.sampleId()); values.put("limit", 50_001);
        BoundSql lockSql = sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.techpack.mapper.TechPackMapper.lockSamplePacks").getBoundSql(values);
        assertThat(lockSql.getSql()).doesNotContain("owner_snapshot").doesNotContain("p.*");
        String overflowStyle = unique("FS");
        List<String> columns = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            var locked = packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 50_001);
            assertThat(locked).hasSize(64); assertThat(locked).isSortedAccordingTo(java.util.Comparator.comparing(TechPackMapper.SamplePackIdentity::id));
            long style = fixtureStyle(f, overflowStyle);
            assertThat(packs.adoptSamplePacks(namespace.tenant(), f.company(), made.sampleId(), style, f.creator())).isZero();
            List<String> projection = jdbc.query(lockSql.getSql(), ps -> bind(ps, lockSql, values), rs -> {
                var names = new ArrayList<String>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) names.add(rs.getMetaData().getColumnLabel(i));
                return List.copyOf(names);
            });
            // Service 必须把更新数与已锁定 64 行比较并回滚；这里显式回滚这个故障注入事务。
            status.setRollbackOnly(); return projection;
        });
        assertThat(columns).containsExactly("id", "style_id", "sample_id", "company_id", "stage", "status", "version", "version_lock");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE code=?", Long.class, overflowStyle)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tp_tech_pack WHERE sample_id=? AND style_id IS NOT NULL", Long.class, made.sampleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT version_lock FROM tp_tech_pack WHERE sample_id=? AND version=64", Integer.class, made.sampleId())).isEqualTo(Integer.MAX_VALUE);
        values.put("beforeVersion", null); values.put("limit", 11);
        BoundSql listSql = sessions.getConfiguration().getMappedStatement("com.lingmao.scm.module.techpack.mapper.TechPackMapper.selectSamplePacks").getBoundSql(values);
        assertThat(listSql.getSql()).doesNotContain("owner_snapshot").doesNotContain("p.*");
        var first = packs.selectSamplePacks(namespace.tenant(), f.company(), made.sampleId(), null, 11);
        assertThat(first).hasSize(11); assertThat(first.getFirst().version()).isEqualTo(64);
        var next = packs.selectSamplePacks(namespace.tenant(), f.company(), made.sampleId(), first.getLast().version(), 11);
        assertThat(next.getFirst().version()).isEqualTo(53); assertThat(packs.countSamplePacks(namespace.tenant(), f.company(), made.sampleId())).isEqualTo(64);
        evidence("light-pack-projections.json", Map.of("lockSql", lockSql.getSql(), "listSql", listSql.getSql(), "persistedSnapshotBytes", bytes,
                "returnedColumns", columns, "lockedHeaders", 64, "adoptedOnOverflow", 0, "first", first, "next", next));
    }

    @Test
    void inverseRawPackWriterFailsNowaitAndCannotDeadlockTheNormalOwnerFirstPath() throws Exception {
        Fixture f = fixture(); Created made = passed(f);
        long pack = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            return pack(made.sampleId(), 1, 0, "DRAFT", null);
        });
        CountDownLatch ownerLocked = new CountDownLatch(1); CountDownLatch writerDone = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger(); AtomicInteger secondPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> {
                login(f.creator(), true);
                try { return transactions.execute(status -> {
                    firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null); ownerLocked.countDown();
                    await(writerDone); return packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 101).size();
                }); } finally { TestIdentity.clear(); }
            });
            var raw = pool.submit(() -> {
                await(ownerLocked); long start = System.nanoTime();
                try { Throwable failure = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                    secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    jdbc.update("UPDATE tp_tech_pack SET version_note='inverse writer',version_lock=version_lock+1 WHERE id=?", pack);
                }));
                    String state = sqlState(failure);
                    return Map.of("sqlState", state == null ? "NONE" : state,
                            "elapsedMs", Duration.ofNanos(System.nanoTime() - start).toMillis());
                } finally { writerDone.countDown(); }
            });
            var rejected = raw.get(10, TimeUnit.SECONDS); assertThat(rejected.get("sqlState")).isEqualTo("55P03");
            assertThat((long) rejected.get("elapsedMs")).isLessThan(4_000);
            assertThat(owner.get(10, TimeUnit.SECONDS)).isEqualTo(1); assertThat(firstPid.get()).isNotEqualTo(secondPid.get());
            evidence("pack-inverse-lock-nowait.json", Map.of("ownerBackend", firstPid.get(), "writerBackend", secondPid.get(), "rejection", rejected));
        }
    }

    @Test
    void sampleTombstoneCleansOnlyItsObjectLinksAndKeepsLibraryAndAppendOnlyDiffs() throws Exception {
        Fixture f = fixture(); Created made = draft(f); String name = unique("sample-tag");
        var before = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
            return tags.replaceBindings(new TagRepository.Target(TagObjectType.SAMPLE, made.sampleId(), f.company()),
                    0, List.of(name), Map.of(), List.of()).after();
        });
        long tag = before.links().getFirst().tag().id();
        transactions.executeWithoutResult(status -> samples.deleteDraft(samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE object_type='SAMPLE' AND object_id=?", Long.class, made.sampleId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_link WHERE tag_id=?", Long.class, tag)).isZero();
        assertThat(tags.get(tag).name()).isEqualTo(name);
        var deleted = jdbc.queryForList("""
                SELECT table_name FROM sys_data_log WHERE root_table='dev_sample' AND root_id=? AND action='DELETE'
                    AND table_name IN ('biz_tag_link','biz_tag_object') ORDER BY id
                """, String.class, made.sampleId());
        assertThat(deleted).containsExactly("biz_tag_link", "biz_tag_object");
        rejected("23503", () -> jdbc.update("INSERT INTO biz_tag_object(tenant_key,object_type,object_id,company_id) VALUES(?,'SAMPLE',?,?)",
                namespace.tenant(), made.sampleId(), f.company()));
        evidence("sample-tombstone-tags.json", Map.of("sampleId", made.sampleId(), "libraryTagId", tag, "retainedLibrary", tags.get(tag), "deleteDiffOrder", deleted));
    }

    @Test
    void firstTagAssociationCannotKeepOldCompanyAfterWaitingForARealCompanyChange() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        Map<String, Object> result = ownerChangeAgainstFirstTag(f, made, false);
        assertThat(jdbc.queryForObject("SELECT company_id FROM dev_sample WHERE id=?", Long.class, made.sampleId())).isEqualTo(f.otherCompany());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE object_type='SAMPLE' AND object_id=?", Long.class, made.sampleId())).isZero();
        login(f.creator(), true);
        var linked = transactions.execute(status -> {
            samples.lock(f.otherCompany(), made.sampleId(), DataScope.current(false), 1, null);
            return tags.replaceBindings(new TagRepository.Target(TagObjectType.SAMPLE, made.sampleId(), f.otherCompany()),
                    0, List.of(unique("current-company-tag")), Map.of(), List.of()).after();
        });
        assertThat(linked.links()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT company_id FROM biz_tag_object WHERE object_type='SAMPLE' AND object_id=?", Long.class, made.sampleId())).isEqualTo(f.otherCompany());
        evidence("sample-company-vs-first-tag.json", result);
    }

    @Test
    void firstTagAssociationWaitsForRealDraftDeletionAndCannotLeaveAnOrphan() throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        Map<String, Object> result = ownerChangeAgainstFirstTag(f, made, true);
        assertThat(jdbc.queryForObject("SELECT deleted_token FROM dev_sample WHERE id=?", Long.class, made.sampleId())).isEqualTo(made.sampleId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM biz_tag_object WHERE object_type='SAMPLE' AND object_id=?", Long.class, made.sampleId())).isZero();
        evidence("sample-delete-vs-first-tag.json", result);
    }

    private Map<String, Object> ownerChangeAgainstFirstTag(Fixture f, Created made, boolean remove) throws Exception {
        CountDownLatch ownerChanged = new CountDownLatch(1); CountDownLatch associationStarted = new CountDownLatch(1);
        AtomicInteger ownerPid = new AtomicInteger(); AtomicInteger associationPid = new AtomicInteger();
        AtomicBoolean observedRealWait = new AtomicBoolean();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> {
                login(f.creator(), true);
                try { return transactions.execute(status -> {
                    ownerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    var locked = samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
                    if (remove) samples.deleteDraft(locked);
                    else { locked.sample().setCompanyId(f.otherCompany()); samples.updateSample(locked, locked.sample()); }
                    ownerChanged.countDown(); await(associationStarted);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                    while (System.nanoTime() < deadline) {
                        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?", Boolean.class, associationPid.get()))) {
                            observedRealWait.set(true); break;
                        }
                        try { TimeUnit.MILLISECONDS.sleep(5); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
                    }
                    assertThat(observedRealWait).as("second physical PostgreSQL connection waited for the owner row").isTrue();
                    return 1;
                }); } finally { TestIdentity.clear(); }
            });
            var association = pool.submit(() -> {
                await(ownerChanged); login(f.creator(), true);
                try {
                    Throwable failed = catchThrowable(() -> transactions.executeWithoutResult(status -> {
                        associationPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        associationStarted.countDown();
                        samples.lock(f.company(), made.sampleId(), DataScope.current(false), 0, null);
                        tags.replaceBindings(new TagRepository.Target(TagObjectType.SAMPLE, made.sampleId(), f.company()),
                                0, List.of(unique("stale-owner-tag")), Map.of(), List.of());
                    }));
                    assertThat(failed).isInstanceOf(BizException.class);
                    return ((BizException) failed).getCode();
                } finally { TestIdentity.clear(); }
            });
            assertThat(owner.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            int rejected = association.get(10, TimeUnit.SECONDS); assertThat(rejected).isEqualTo(404);
            assertThat(ownerPid.get()).isNotEqualTo(associationPid.get());
            return Map.of("sampleId", made.sampleId(), "remove", remove, "ownerBackend", ownerPid.get(),
                    "associationBackend", associationPid.get(), "observedRowLockWait", observedRealWait.get(), "staleOwnerResult", rejected);
        }
    }

    private Created passed(Fixture f) {
        Created made = draft(f); dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        review(f, made.sampleId(), SampleReviewResult.PASS); return made;
    }

    private long pack(long sampleId, int version, int versionLock, String state, String snapshot) {
        return jdbc.queryForObject("""
                INSERT INTO tp_tech_pack(sample_id,stage,version,status,version_note,version_lock,owner_snapshot)
                VALUES(?,'DEV',?,?,'S1 owner guard fixture',?,CAST(? AS jsonb)) RETURNING id
                """, Long.class, sampleId, version, state, versionLock, snapshot);
    }

    private long fixtureStyle(Fixture f, String code) {
        return jdbc.queryForObject("""
                INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id)
                VALUES(?,'S1 移交目标','FS','S1_BRAND',?,2026,'SPRING','OWN',?) RETURNING id
                """, Long.class, code, f.category(), f.sizeGroup());
    }

    private void bind(java.sql.PreparedStatement statement, BoundSql sql, Map<String, Object> parameters) throws java.sql.SQLException {
        var meta = sessions.getConfiguration().newMetaObject(parameters);
        for (int i = 0; i < sql.getParameterMappings().size(); i++) {
            String property = sql.getParameterMappings().get(i).getProperty();
            statement.setObject(i + 1, sql.hasAdditionalParameter(property) ? sql.getAdditionalParameter(property) : meta.getValue(property));
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrency gate timed out"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
}
