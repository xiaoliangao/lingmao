package com.lingmao.scm.module.dev.repository;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.SampleReviewResult;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.techpack.mapper.TechPackMapper;
import com.lingmao.scm.security.DataScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A corrupt legacy lock value must refuse the whole group; neither the healthy pack nor the new style survives. */
@IntegrationTest
class S1SampleTransferBoundsIT extends SampleTestSupport {
    @Autowired TechPackMapper packs;

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MAX_VALUE})
    void invalidLowerOrUpperLockBoundRefusesTheWholeAdoption(int invalidLock) throws Exception {
        Fixture f = fixture(); Created made = draft(f);
        dispatch(f, made.sampleId(), MakeWay.INTERNAL); send(f, made.sampleId());
        review(f, made.sampleId(), SampleReviewResult.PASS);
        transactions.executeWithoutResult(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            // V20 has no version_lock CHECK. Inject the historical corruption without disabling any DB guard.
            jdbc.update("""
                    INSERT INTO tp_tech_pack(sample_id,stage,version,status,version_lock)
                    VALUES(?,'DEV',1,'VOIDED',?),(?,'DEV',2,'DRAFT',0)
                    """, made.sampleId(), invalidLock, made.sampleId());
        });
        String styleCode = unique("FS");
        List<Map<String, Object>> before = owners(made.sampleId());
        int changed = transactions.execute(status -> {
            samples.lock(f.company(), made.sampleId(), DataScope.current(false), 3, null);
            long style = jdbc.queryForObject("""
                    INSERT INTO sty_style(code,name,style_class,brand,category_id,year,season,source,size_group_id)
                    VALUES(?,'S1 version bound target','FS','S1_BRAND',?,2026,'SPRING','OWN',?) RETURNING id
                    """, Long.class, styleCode, f.category(), f.sizeGroup());
            assertThat(packs.lockSamplePacks(namespace.tenant(), f.company(), made.sampleId(), 50_001)).hasSize(2);
            int rows = packs.adoptSamplePacks(namespace.tenant(), f.company(), made.sampleId(), style, f.creator());
            assertThat(rows).isZero();
            assertThat(owners(made.sampleId())).isEqualTo(before);
            status.setRollbackOnly(); // The Service compares the update count to the two locked heads and rolls back.
            return rows;
        });
        assertThat(changed).isZero();
        assertThat(owners(made.sampleId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sty_style WHERE code=?", Long.class, styleCode)).isZero();
        assertThat(snapshot(f, made.sampleId()).sample().getStatus()).isEqualTo(SampleStatus.PASSED);
        evidence("transfer-lock-bound-" + invalidLock + ".json", Map.of("invalidLock", invalidLock,
                "lockedHeads", 2, "updatedRows", changed, "ownersBefore", before, "ownersAfter", owners(made.sampleId()),
                "styleRolledBack", true, "sampleStatus", "PASSED"));
    }

    private List<Map<String, Object>> owners(long sampleId) {
        return jdbc.queryForList("SELECT id,style_id,sample_id,version,version_lock FROM tp_tech_pack WHERE sample_id=? ORDER BY id", sampleId);
    }
}
