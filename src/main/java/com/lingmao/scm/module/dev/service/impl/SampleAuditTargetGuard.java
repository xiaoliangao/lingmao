package com.lingmao.scm.module.dev.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.dev.service.SampleService;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** 样衣八表共享真实样衣根；四张复合键表只作为根查询的事件出现。 */
@Component
public class SampleAuditTargetGuard implements AuditTargetGuard {
    private static final Set<String> TABLES = Set.of("dev_sample", "dev_sample_order", "dev_sample_node", "dev_sample_review",
            "dev_sample_color", "dev_sample_order_color", "dev_sample_order_size", "dev_sample_reviewer");
    private final SampleService samples;
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;

    public SampleAuditTargetGuard(SampleService samples, JdbcTemplate jdbc, DeploymentNamespace namespace) {
        this.samples = samples; this.jdbc = jdbc; this.namespace = namespace;
    }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void check(String table, long id) { check(table, id, this::checkHistoricalRoot); }

    @Override public Root check(String table, long id, HistoricalRootGuard historical) {
        if (id <= 0) throw missing();
        String sql = switch (table) {
            case "dev_sample" -> "SELECT company_id,id AS sample_id,NULL::bigint AS order_id FROM dev_sample WHERE tenant_key=? AND id=?";
            case "dev_sample_order" -> "SELECT company_id,sample_id,id AS order_id FROM dev_sample_order WHERE tenant_key=? AND id=?";
            case "dev_sample_node" -> """
                    SELECT o.company_id,o.sample_id,o.id AS order_id FROM dev_sample_node n
                    JOIN dev_sample_order o ON o.tenant_key=n.tenant_key AND o.company_id=n.company_id AND o.id=n.sample_order_id
                    WHERE n.tenant_key=? AND n.id=?
                    """;
            case "dev_sample_review" -> """
                    SELECT o.company_id,o.sample_id,o.id AS order_id FROM dev_sample_review r
                    JOIN dev_sample_order o ON o.tenant_key=r.tenant_key AND o.company_id=r.company_id AND o.id=r.sample_order_id
                    WHERE r.tenant_key=? AND r.id=?
                    """;
            default -> throw missing(); // 不能拿父 ID 冒充颜色/尺码/评审人的行 ID。
        };
        List<Identity> found = jdbc.query(sql, (rs, n) -> new Identity(rs.getLong("company_id"),
                rs.getLong("sample_id"), rs.getObject("order_id", Long.class)), namespace.tenant(), id);
        if (found.size() != 1) throw missing();
        Identity actual = found.getFirst();
        // 只使用数据库实际主体；服务校验必需公司头、普通当前负责人范围和 dev:sample:list。
        switch (table) {
            case "dev_sample" -> samples.checkReadable(actual.companyId(), actual.sampleId());
            case "dev_sample_order", "dev_sample_node" -> samples.checkOrderReadable(actual.companyId(), actual.orderId());
            case "dev_sample_review" -> samples.checkReviewReadable(actual.companyId(), id);
            default -> throw missing();
        }
        return new Root("dev_sample", actual.sampleId());
    }

    @Override public void checkEventRoot(Root root, HistoricalRootGuard historical) {
        if (!root.table().equals("dev_sample")) throw missing();
        check("dev_sample", root.id());
    }

    // tombstone 保留事实，但当前领域读取仍拒绝；不借历史日志扩展 SELF 或重新开放已删对象。
    private record Identity(long companyId, long sampleId, Long orderId) { }
    private static BizException missing() { return new BizException(404, "样衣审计对象不存在或无权查看"); }
}
