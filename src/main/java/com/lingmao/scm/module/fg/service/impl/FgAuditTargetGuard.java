package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.fg.mapper.FgDocItemMapper;
import com.lingmao.scm.module.fg.mapper.FgDocMapper;
import com.lingmao.scm.module.fg.mapper.FgStockMapper;
import com.lingmao.scm.module.fg.service.FgDocService;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;
import java.util.Set;

/** 字段审计沿用成品对象权限；历史 DELETE 证明只对原有全数据审计者开放。 */
@Component
public class FgAuditTargetGuard implements AuditTargetGuard {
    private static final Set<String> TABLES = Set.of("fg_doc", "fg_doc_item", "fg_stock");
    private final FgDocService docs;
    private final FgDocMapper docMapper;
    private final FgDocItemMapper itemMapper;
    private final FgStockMapper stockMapper;
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final JsonMapper json;

    public FgAuditTargetGuard(FgDocService docs, FgDocMapper docMapper, FgDocItemMapper itemMapper,
                              FgStockMapper stockMapper, JdbcTemplate jdbc, DeploymentNamespace namespace, JsonMapper json) {
        this.docs = docs;
        this.docMapper = docMapper;
        this.itemMapper = itemMapper;
        this.stockMapper = stockMapper;
        this.jdbc = jdbc;
        this.namespace = namespace;
        this.json = json;
    }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void checkHistoricalRoot(String table, long id) {
        if (!table.equals("fg_doc") || !LoginUser.hasAuthority("fg:doc:list") || docMapper.selectById(id) != null) throw missing();
        Long company = CompanyContext.header(true);
        if (company == null) throw new BizException(400, "审计读取需要明确主体");
        checkDeletedDoc(id, company);
    }

    @Override public void check(String table, long id) {
        if (!TABLES.contains(table)) throw missing();
        String permission = table.equals("fg_stock") ? "fg:stock:list" : "fg:doc:list";
        if (!LoginUser.hasAuthority(permission)) throw missing();
        Long company = CompanyContext.header(true);
        if (company == null) throw new BizException(400, "审计读取需要明确主体");
        switch (table) {
            case "fg_doc" -> checkDoc(id, company);
            case "fg_doc_item" -> {
                var item = itemMapper.selectById(id);
                if (item != null) {
                    checkDoc(item.getDocId(), company);
                } else {
                    var deleted = lastDelete(table, id);
                    long docId = oldId(deleted, "doc_id");
                    if (!deleted.rootTable().equals("fg_doc") || deleted.rootId() != docId) throw missing();
                    checkDoc(docId, company);
                }
            }
            case "fg_stock" -> {
                var stock = stockMapper.selectById(id);
                if (stock != null) {
                    if (!Objects.equals(stock.getCompanyId(), company)) throw missing();
                } else {
                    var deleted = lastDelete(table, id);
                    if (!deleted.rootTable().equals(table) || deleted.rootId() != id
                            || oldId(deleted, "company_id") != company) throw missing();
                }
            }
            default -> throw missing();
        }
    }

    private void checkDoc(long id, long company) {
        // 只在实际行缺失时查历史；当前存在但无权的对象不能用历史事件绕过 DataScope。
        if (docMapper.selectById(id) != null) {
            docs.checkVisible(id, company);
            return;
        }
        checkDeletedDoc(id, company);
    }

    private void checkDeletedDoc(long id, long company) {
        var deleted = lastDelete("fg_doc", id);
        if (!deleted.rootTable().equals("fg_doc") || deleted.rootId() != id
                || oldId(deleted, "company_id") != company) throw missing();
    }

    private record DeletedEvent(String action, String rootTable, long rootId, JsonNode diff) { }

    private DeletedEvent lastDelete(String table, long id) {
        if (!LoginUser.hasAuthority("system:audit:query") || !LoginUser.hasAuthority("data:all")) throw missing();
        var rows = jdbc.query("""
                SELECT action,root_table,root_id,diff FROM sys_data_log
                WHERE tenant_key=? AND table_name=? AND row_id=? ORDER BY id DESC LIMIT 1
                """, (rs, n) -> new DeletedEvent(rs.getString("action"), rs.getString("root_table"),
                rs.getLong("root_id"), json.readTree(rs.getString("diff"))), namespace.tenant(), table, id);
        if (rows.size() != 1 || !rows.getFirst().action().equals("DELETE")) throw missing();
        return rows.getFirst();
    }

    private static long oldId(DeletedEvent event, String field) {
        JsonNode value = event.diff().path(field).path("old");
        if (!value.isString() || !value.stringValue().matches("[1-9][0-9]{0,18}")) throw missing();
        try { return Long.parseLong(value.stringValue()); }
        catch (NumberFormatException invalid) { throw missing(); }
    }

    private static BizException missing() { return new BizException(404, "成品审计对象不存在或无权查看"); }
}
