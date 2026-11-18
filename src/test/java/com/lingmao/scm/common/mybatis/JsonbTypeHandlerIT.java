package com.lingmao.scm.common.mybatis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.entity.DocLog;
import com.lingmao.scm.module.biz.mapper.DocLogMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSONB 类型处理器在真库上：用 biz_doc_log.extra 这一列测（legacy_attrs 的表第 2 批才建）。
 * doc_id 每个测试随机，测完按它删掉
 */
@IntegrationTest
class JsonbTypeHandlerIT {

    @Autowired
    private DocLogMapper docLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final long docId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);

    @AfterEach
    void cleanUp() {
        // Append-only audit rows remain in this disposable test database.
    }

    /**
     * 规则：Map 写进 JSONB 列（不报"jsonb 列给了 varchar"），库里存的是 JSON 对象、能用 ->> 取值；
     * 按 id 查、按条件查都原样读回来（autoResultMap），中文、数字、数组都在
     */
    @Test
    void mapRoundTrip() {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("产地", "广州");
        extra.put("undone", List.of("CG202611150001", "CG202611150002"));
        extra.put("count", 2);
        DocLog log = newLog("unaudit");
        log.setExtra(extra);
        docLogMapper.insert(log);

        assertThat(jdbcTemplate.queryForObject("SELECT jsonb_typeof(extra) FROM biz_doc_log WHERE id = ?",
                String.class, log.getId())).isEqualTo("object");
        assertThat(jdbcTemplate.queryForObject("SELECT extra ->> '产地' FROM biz_doc_log WHERE id = ?",
                String.class, log.getId())).isEqualTo("广州");
        assertThat(docLogMapper.selectById(log.getId()).getExtra()).isEqualTo(extra);
        assertThat(docLogMapper.selectList(new LambdaQueryWrapper<DocLog>().eq(DocLog::getDocId, docId))
                .getFirst().getExtra()).isEqualTo(extra);
    }

    /** 规则：没有附加信息的动作，extra 是 SQL 的 NULL（不是 JSON 的 null），读回来也是 null；改成有值能写进去 */
    @Test
    void nullStaysNull_andFollowupAppends() {
        DocLog log = newLog("create");
        docLogMapper.insert(log);

        assertThat(jdbcTemplate.queryForObject("SELECT extra IS NULL FROM biz_doc_log WHERE id = ?",
                Boolean.class, log.getId())).isTrue();
        assertThat(docLogMapper.selectById(log.getId()).getExtra()).isNull();

        log.setExtra(Map.of("level", 1));
        DocLog followup = newLog("audit");
        followup.setExtra(Map.of("level", 1));
        docLogMapper.insert(followup);
        assertThat(docLogMapper.selectById(log.getId()).getExtra()).isNull();
        assertThat(docLogMapper.selectById(followup.getId()).getExtra()).isEqualTo(Map.of("level", 1));
    }

    private DocLog newLog(String action) {
        DocLog log = new DocLog();
        log.setDocType(BizType.SUPPLIER);
        log.setDocId(docId);
        log.setAction(action);
        return log;
    }
}
