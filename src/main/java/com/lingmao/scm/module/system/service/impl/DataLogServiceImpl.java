package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.log.AuditContext;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.dto.DataLogView;
import com.lingmao.scm.module.system.service.AuditTargetGuard;
import com.lingmao.scm.module.system.service.DataLogService;
import com.lingmao.scm.security.FieldPermissions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class DataLogServiceImpl implements DataLogService {
    private static final Set<String> TABLES = Set.of("bas_company","bas_customer","bas_material","bas_material_sku","bas_warehouse",
            "sty_style","sup_supplier","sup_supplier_change","prc_price","tp_tech_pack","tp_bom_item","tp_bom_item_sku",
            "tp_bom_item_size","tp_process","tp_cost_item","ord_order","ord_order_item","ord_change","ord_change_item",
            "pur_order","pur_order_item","prd_order","prd_order_item","inv_doc","inv_doc_item","inv_stock","inv_count","inv_count_item",
            "sys_user","sys_role","fg_doc","fg_doc_item","fg_stock");
    private static final Set<String> FG_TABLES = Set.of("fg_doc", "fg_doc_item", "fg_stock");
    /** 安全审计只读同一份明确白名单，不复用价格权限，也不透出身份资料或凭据。 */
    private static final Map<String, Set<String>> SECURITY_FIELDS = Map.of(
            "sys_user", Set.of("id", "status", "deleted", "default_company_id", "session_version", "designer_no_locked"),
            "sys_role", Set.of("id", "code", "status", "deleted", "sort", "data_scope"),
            "sys_user_role", Set.of("user_id", "role_id"),
            "sys_role_menu", Set.of("role_id", "menu_id"));
    private static final Set<String> SUPPLIER_FIELDS = Set.of("name","creditCode","settleMethod","creditDays","taxPct","invoiceType",
            "bankName","bankAccount","bankAccountName");
    private final JdbcTemplate jdbc;
    private final DeploymentNamespace namespace;
    private final JsonMapper json;
    private final Map<String, AuditTargetGuard> targetGuards;
    public DataLogServiceImpl(JdbcTemplate jdbc, DeploymentNamespace namespace, JsonMapper json, List<AuditTargetGuard> guards) {
        this.jdbc = jdbc; this.namespace = namespace;
        this.json = json.rebuild().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        Map<String, AuditTargetGuard> registered = new LinkedHashMap<>();
        for (AuditTargetGuard guard : guards) for (String table : guard.tables()) {
            if (registered.putIfAbsent(table, guard) != null) throw new IllegalStateException("Duplicate audit target guard");
        }
        if (!registered.keySet().containsAll(FG_TABLES)) throw new IllegalStateException("Finished-goods audit guard is required");
        this.targetGuards = Map.copyOf(registered);
    }

    @Override @Transactional
    public List<DataLogView> list(String table, Long rowId, String rootTable, Long rootId,
                                  Long transactionId, Long beforeId, int limit) {
        boolean root = rootTable != null || rootId != null;
        String targetTable = root ? rootTable : table;
        Long targetId = root ? rootId : rowId;
        if (targetTable == null || !TABLES.contains(targetTable) || targetId == null || targetId < 1
                || root && (table != null || rowId != null) || transactionId != null && transactionId < 1
                || beforeId != null && beforeId < 1 || limit < 1 || limit > 100)
            throw new BizException(400, "审计查询参数不正确");
        AuditTargetGuard guard = targetGuards.get(targetTable);
        if (guard != null) guard.check(targetTable, targetId);
        var parameters = new ArrayList<Object>(List.of(namespace.tenant(), targetTable, targetId, beforeId == null ? Long.MAX_VALUE : beforeId));
        String predicate = root ? "root_table=? AND root_id=?" : "table_name=? AND row_id=?";
        if (transactionId != null) parameters.add(transactionId);
        parameters.add(limit);
        List<DataLogView> rows = jdbc.query("SELECT * FROM sys_data_log WHERE tenant_key=? AND " + predicate + " AND id<?"
                + (transactionId == null ? "" : " AND transaction_id=?") + " ORDER BY id DESC LIMIT ?", (rs, n) -> {
            String actualTable = rs.getString("table_name");
            String domain = rs.getString("price_domain");
            ObjectNode source = (ObjectNode) json.readTree(rs.getString("diff"));
            ObjectNode diff;
            if (FG_TABLES.contains(actualTable)) {
                // FG 使用订单成交价权限，不能因默认 COST 分支或历史错误域标记放出价格。
                diff = project(actualTable, FieldPermissions.ORDER, source);
            } else if ("SECURITY".equals(domain)) {
                diff = projectSecurity(actualTable, source);
            } else {
                String permission = switch (domain) {
                    case "ORDER" -> FieldPermissions.ORDER;
                    case "PRODUCTION" -> FieldPermissions.PRODUCTION;
                    default -> FieldPermissions.COST;
                };
                diff = project(actualTable, permission, source);
            }
            return new DataLogView(rs.getString("id"), actualTable, rs.getString("row_id"), rowKey(actualTable, rs.getString("row_key")), rs.getString("root_table"),
                    rs.getString("root_id"), rs.getString("action"), rs.getString("previous_row_id"), diff,
                    rs.getString("actor_user_id"), rs.getString("client_ip"), rs.getString("request_id"),
                    rs.getString("operation"), rs.getString("transaction_id"), rs.getObject("created_at", OffsetDateTime.class));
        }, parameters.toArray());
        // 先记下这次受控查询的对象，再放出敏感历史；记录失败就中止响应。
        Map<String,Object> metadata = new LinkedHashMap<>();
        metadata.put(root ? "rootTable" : "table", targetTable); metadata.put(root ? "rootId" : "rowId", targetId.toString());
        if (transactionId != null) metadata.put("transactionId", transactionId.toString());
        if (beforeId != null) metadata.put("beforeId", beforeId.toString());
        metadata.put("limit", limit); metadata.put("rows", rows.size());
        AuditContext actor = AuditContext.current();
        int recorded = jdbc.update("""
                INSERT INTO sys_oper_log(user_id,username,module,action,method,request_method,request_uri,params,success,cost_ms,ip,trace_id)
                SELECT id,username,'字段审计','查看','DataLogService.list','GET','/api/data-logs',?,true,0,?,? FROM sys_user WHERE id=?
                """, json.writeValueAsString(metadata), actor.ip(), actor.requestId(), actor.actor().isEmpty() ? null : Long.valueOf(actor.actor()));
        if (recorded != 1) throw new BizException(403, "审计查询需要有效登录身份");
        return rows;
    }

    private ObjectNode projectSecurity(String table, ObjectNode diff) {
        Set<String> allowed = SECURITY_FIELDS.get(table);
        if (allowed == null) throw new IllegalStateException("Unexpected security audit table");
        for (String name : diff.properties().stream().map(Map.Entry::getKey).toList()) {
            JsonNode change = diff.get(name);
            if (!allowed.contains(name) || !change.isObject() || change.path("old").isContainer()
                    || change.path("new").isContainer()) diff.remove(name);
        }
        return diff;
    }

    private ObjectNode project(String table, String permission, ObjectNode diff) {
        for (String name : diff.properties().stream().map(Map.Entry::getKey).toList()) {
            if (table.equals("sup_supplier_change") && name.equals("changes")) {
                ObjectNode change = json.createObjectNode();
                for (String side : List.of("old", "new")) {
                    JsonNode source = diff.get(name).path(side);
                    if (source.isNull()) { change.putNull(side); continue; }
                    var allowed = json.createArrayNode();
                    if (source.isArray()) for (JsonNode item : source) {
                        String field = item.path("field").asText();
                        if (!SUPPLIER_FIELDS.contains(field) || blocked(field, permission)) continue;
                        ObjectNode value = json.createObjectNode(); value.put("field", field);
                        for (String valueSide : List.of("before", "after")) {
                            JsonNode scalar = item.path(valueSide);
                            if (scalar.isValueNode()) value.set(valueSide, scalar);
                        }
                        allowed.add(value);
                    }
                    change.set(side, allowed);
                }
                diff.set(name, change);
                continue;
            }
            JsonNode old = diff.get(name).path("old"), next = diff.get(name).path("new");
            String key = name.toLowerCase(Locale.ROOT);
            if (blocked(name, permission) || old.isContainer() || next.isContainer()
                    || key.contains("remark") || key.contains("reason") || key.contains("content")
                    || key.equals("summary") || key.equals("text") || key.equals("version_note") || key.equals("follow_note")) {
                diff.remove(name);
            } else if (key.equals("id") || key.endsWith("_id") || key.endsWith("_by")) {
                ObjectNode values = (ObjectNode) diff.get(name);
                for (String side : List.of("old","new")) if (values.hasNonNull(side)) values.put(side, values.get(side).asText());
            }
        }
        return diff;
    }

    private Map<String, String> rowKey(String table, String source) {
        if (source == null) return null;
        Set<String> keys = switch (table) {
            case "sys_user_role" -> Set.of("user_id", "role_id");
            case "sys_role_menu" -> Set.of("role_id", "menu_id");
            default -> throw new IllegalStateException("Unexpected audit relationship identity");
        };
        JsonNode parsed = json.readTree(source);
        if (!parsed.isObject() || parsed.size() != keys.size()) throw new IllegalStateException("Invalid audit relationship identity");
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : keys.stream().sorted().toList()) {
            JsonNode value = parsed.path(key);
            if (!value.isString() || !value.stringValue().matches("[1-9][0-9]{0,18}"))
                throw new IllegalStateException("Invalid audit relationship identity");
            result.put(key, value.stringValue());
        }
        return Map.copyOf(result);
    }

    private static boolean blocked(String name, String permission) {
        String key = name.replace("_", "").toLowerCase(Locale.ROOT);
        if (key.equals("tagprice") || key.equals("esttagprice")) return false;
        if (key.contains("bank")) return !FieldPermissions.has(FieldPermissions.BANK);
        if (key.contains("phone") || key.contains("contact") || key.contains("wechat") || key.contains("address") || key.contains("creditcode"))
            return !FieldPermissions.has(FieldPermissions.CONTACT);
        if (key.contains("price") || key.contains("cost") || key.contains("amount") || key.contains("fee") || key.contains("tax") || key.contains("freight"))
            return !FieldPermissions.has(permission);
        return false;
    }
}
