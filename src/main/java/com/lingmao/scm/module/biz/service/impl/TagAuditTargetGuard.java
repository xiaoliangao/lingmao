package com.lingmao.scm.module.biz.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.biz.entity.TagObjectType;
import com.lingmao.scm.module.biz.repository.TagRepository;
import com.lingmao.scm.module.biz.service.TagService;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import com.lingmao.scm.security.LoginUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** 公共字段审计只读真实目标；不创建 registry、不读缓存、不开放 PUBLIC_TAG 附件对象。 */
@Component
public class TagAuditTargetGuard implements AuditTargetGuard {
    private static final Map<String, TagObjectType> TYPES = Map.ofEntries(
            Map.entry("sty_style", TagObjectType.STYLE), Map.entry("bas_material", TagObjectType.MATERIAL),
            Map.entry("sup_supplier", TagObjectType.SUPPLIER), Map.entry("tp_tech_pack", TagObjectType.TECH_PACK),
            Map.entry("ord_order", TagObjectType.ORDER), Map.entry("mrp_calc", TagObjectType.MRP_CALC),
            Map.entry("pur_order", TagObjectType.PURCHASE_ORDER), Map.entry("inv_doc", TagObjectType.INV_DOC),
            Map.entry("inv_count", TagObjectType.INV_COUNT), Map.entry("prd_order", TagObjectType.PRODUCTION_ORDER),
            Map.entry("prd_receipt", TagObjectType.PRODUCTION_RECEIPT), Map.entry("fg_doc", TagObjectType.FG_DOC),
            Map.entry("dev_sample", TagObjectType.SAMPLE));
    /** V36 触发器固化的真实父链；不接受任意表名，也不反向扫描其他业务域。 */
    private static final Map<String, String> CHILD_ROOTS = Map.ofEntries(
            Map.entry("bas_material_sku", "bas_material"), Map.entry("sup_supplier_change", "sup_supplier"),
            Map.entry("tp_bom_item", "tp_tech_pack"), Map.entry("tp_bom_item_sku", "tp_tech_pack"),
            Map.entry("tp_bom_item_size", "tp_tech_pack"), Map.entry("tp_process", "tp_tech_pack"), Map.entry("tp_cost_item", "tp_tech_pack"),
            Map.entry("ord_order_item", "ord_order"), Map.entry("ord_change", "ord_order"), Map.entry("ord_change_item", "ord_order"),
            Map.entry("pur_order_item", "pur_order"), Map.entry("prd_order_item", "prd_order"),
            Map.entry("inv_doc_item", "inv_doc"), Map.entry("inv_count_item", "inv_count"));
    private static final Set<String> TABLES = supportedTables();
    private final TagService tags;
    private final TagRepository repository;
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final JsonMapper json;

    public TagAuditTargetGuard(TagService tags, TagRepository repository, JdbcTemplate jdbc,
                               DeploymentNamespace namespace, JsonMapper json) {
        this.tags = tags; this.repository = repository; this.jdbc = jdbc; this.namespace = namespace; this.json = json;
    }

    @Override public Set<String> tables() { return TABLES; }

    @Override public void check(String table, long id) {
        check(table, id, this::checkHistoricalRoot);
    }

    @Override public Root check(String table, long id, HistoricalRootGuard historical) {
        if (!TABLES.contains(table) || id <= 0) throw missing();
        Root root = CHILD_ROOTS.containsKey(table) ? resolveChild(table, id) : switch (table) {
            case "biz_tag_object", "biz_tag_link" -> resolveAssociation(table, id);
            default -> new Root(table, id);
        };
        authorize(root, historical);
        return root;
    }

    @Override public void checkEventRoot(Root root, HistoricalRootGuard historical) {
        authorize(root, historical);
    }

    private void authorize(Root root, HistoricalRootGuard historical) {
        if (root.id() <= 0) throw missing();
        if (root.table().equals("biz_tag")) {
            if (!LoginUser.hasAuthority("biz:tag:list")) throw new BizException(403, "没有查看公共标签的权限");
            repository.get(root.id()); // 同 namespace，归档行仍真实存在；不查看任意对象的关联。
            return;
        }
        TagObjectType type = TYPES.get(root.table());
        if (type == null) throw missing();
        try {
            tags.checkReadable(type, root.id());
        } catch (BizException denied) {
            // 只有实际 FG 根守卫能证明已删父头；不能用旧字段、请求头或自报布尔值代替授权。
            if (type != TagObjectType.FG_DOC || denied.getCode() != 404) throw denied;
            historical.check(root.table(), root.id());
        }
    }

    private Root resolveAssociation(String table, long id) {
        String sql = table.equals("biz_tag_object")
                ? "SELECT object_type,object_id FROM biz_tag_object WHERE tenant_key=? AND id=?"
                : """
                  SELECT o.object_type,o.object_id FROM biz_tag_link l
                  JOIN biz_tag_object o ON o.tenant_key=l.tenant_key AND o.id=l.object_ref_id
                  WHERE l.tenant_key=? AND l.id=?
                  """;
        List<Root> live = jdbc.query(sql, (rs, n) -> root(rs.getString("object_type"), rs.getLong("object_id")), namespace.tenant(), id);
        if (!live.isEmpty()) return live.getFirst();
        var events = jdbc.query("""
                SELECT action,root_table,root_id,diff FROM sys_data_log
                WHERE tenant_key=? AND table_name=? AND row_id=? ORDER BY id DESC LIMIT 1
                """, (rs, n) -> new DeletedEvent(rs.getString("action"), new Root(rs.getString("root_table"), rs.getLong("root_id")),
                json.readTree(rs.getString("diff"))), namespace.tenant(), table, id);
        if (events.size() != 1 || !events.getFirst().action().equals("DELETE")) throw missing();
        DeletedEvent last = events.getFirst();
        if (!TYPES.containsKey(last.root().table()) || last.root().id() <= 0 || oldId(last.diff(), "id") != id) throw missing();
        if (table.equals("biz_tag_object")) {
            Root old = root(last.diff().path("object_type").path("old").asText(), oldId(last.diff(), "object_id"));
            if (!old.equals(last.root())) throw missing();
        } else {
            oldId(last.diff(), "object_ref_id");
            oldId(last.diff(), "tag_id");
        }
        return last.root();
    }

    private record DeletedEvent(String action, Root root, JsonNode diff) { }

    private Root resolveChild(String table, long id) {
        // 先取未受 beforeId/transactionId 影响的最新真实根，再检查当前领域权限。
        // 旧子行已删除也可读仍存在的父头；所有返回事件仍须匹配这个唯一根。
        List<Root> roots = jdbc.query("""
                SELECT root_table,root_id FROM sys_data_log
                WHERE tenant_key=? AND table_name=? AND row_id=? ORDER BY id DESC LIMIT 1
                """, (rs, n) -> new Root(rs.getString("root_table"), rs.getLong("root_id")), namespace.tenant(), table, id);
        if (roots.size() != 1 || !roots.getFirst().table().equals(CHILD_ROOTS.get(table)) || roots.getFirst().id() <= 0) throw missing();
        return roots.getFirst();
    }

    private static Set<String> supportedTables() {
        var tables = new HashSet<>(TYPES.keySet());
        tables.remove("fg_doc"); // 原有 FG 守卫独占；包括已删父头的严格证明路径。
        tables.remove("dev_sample"); // 样衣域守卫独占；标签事件仍复用真实样衣 read 授权。
        tables.addAll(CHILD_ROOTS.keySet());
        tables.addAll(Set.of("biz_tag", "biz_tag_object", "biz_tag_link"));
        return Set.copyOf(tables);
    }

    private static Root root(String type, long id) {
        return TYPES.entrySet().stream().filter(entry -> entry.getValue().name().equals(type))
                .map(entry -> new Root(entry.getKey(), id)).findFirst().orElseThrow(TagAuditTargetGuard::missing);
    }

    private static long oldId(JsonNode diff, String field) {
        JsonNode value = diff.path(field).path("old");
        if (!value.isString() || !value.stringValue().matches("[1-9][0-9]{0,18}")) throw missing();
        try { return Long.parseLong(value.stringValue()); }
        catch (NumberFormatException invalid) { throw missing(); }
    }

    private static BizException missing() { return new BizException(404, "标签审计对象不存在或无权查看"); }
}
