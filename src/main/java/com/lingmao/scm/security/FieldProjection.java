package com.lingmao.scm.security;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 只裁剪 HTTP 返回的树；缓存里的 DTO、数据库快照和服务端计算都不改。 */
public class FieldProjection {
    private static final Set<String> COST = Set.of("refPrice", "refCost", "estCost", "unitCost", "unitCostPackId",
            "estAmount", "convertedAmount", "buyPrice", "buyPriceDefault", "laborPrice", "laborNote", "ratio",
            "cost", "costMode");
    private static final Set<String> COST_TOTALS = Set.of("fabric", "trim", "labor", "other", "total");
    private static final Set<String> MONEY = Set.of("price", "unitPrice", "purchasePrice", "amount", "totalAmount", "archivePrice", "prevPrice", "nextPrice",
            "fromPrice", "toPrice", "prices", "priceSourceId", "priceChangeReason", "priceFrom", "priceEffectiveDate",
            "priceTaxIncluded", "priceFreightIncluded");
    private static final Set<String> CONTACT = Set.of("contact", "phone", "wechat", "address", "supplierContact", "supplierPhone", "factoryContact", "factoryPhone");
    private static final Set<String> BANK = Set.of("bankName", "bankAccount", "bankAccountName");
    private static final ConcurrentHashMap<Class<?>, Map<String, Field>> FIELDS = new ConcurrentHashMap<>();
    private final JsonMapper json;

    public FieldProjection(JsonMapper json) { this.json = json; }

    public JsonNode project(Object value, String controllerClass) {
        JsonNode tree = json.valueToTree(value);
        visit(value, tree, domain(controllerClass, FieldPermissions.COST), controllerClass.contains(".supplier."), "");
        return tree;
    }

    private void visit(Object source, JsonNode node, String inherited, boolean supplier, String path) {
        if (node == null || node.isNull()) return;
        // 审计行已经按库里存的价格域投影过了。再按系统 controller 统一裁一次 COST，
        // 会把只属于 ORDER 或 PRODUCTION 的历史错删掉。
        if (source instanceof com.lingmao.scm.module.system.dto.DataLogView) return;
        if (node instanceof ArrayNode array) {
            List<?> values = source instanceof Collection<?> c ? new ArrayList<>(c) : List.of();
            for (int i = 0; i < array.size(); i++) visit(i < values.size() ? values.get(i) : array.get(i), array.get(i), inherited, supplier, path);
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        String typeName = source == null ? "" : source.getClass().getName();
        String permission = domain(typeName, inherited);
        supplier |= typeName.contains(".supplier.");
        // 价格档案的域取自真实的数据行或 PriceRef，不取调用方的查询参数。
        if (typeName.startsWith("com.lingmao.scm.module.price.")) {
            Object type = property(source, "type");
            permission = "PROCESS".equals(String.valueOf(type)) ? FieldPermissions.PRODUCTION : FieldPermissions.COST;
        }
        if (typeName.endsWith(".DocNoticeVO") && "ORDER_CHANGE".equals(object.path("source").path("type").asText())
                && !FieldPermissions.has(FieldPermissions.ORDER))
            object.put("content", "上游订单已变更，请查看变更详情");
        if (typeName.endsWith(".DocLogVO") && object.has("action") && "change".equals(object.get("action").asText())
                && permission.equals(FieldPermissions.ORDER) && !FieldPermissions.has(FieldPermissions.ORDER)) object.remove("remark");
        if (typeName.endsWith(".DocLogVO") && object.has("action") && "audit".equals(object.get("action").asText())
                && permission.equals(FieldPermissions.PRODUCTION) && !FieldPermissions.has(FieldPermissions.PRODUCTION)) object.remove("remark");
        if (supplier && typeName.endsWith(".DocLogVO") && object.has("action")
                && Set.of("update", "change", "change-approve", "change-reject", "change-withdraw").contains(object.get("action").asText())
                && !FieldPermissions.has(FieldPermissions.BANK)) object.remove("remark");
        if (typeName.endsWith(".ChangeItemVO") && object.has("changeType") && "PRICE".equals(object.get("changeType").asText())
                && !FieldPermissions.has(FieldPermissions.ORDER)) object.remove("text");
        if (supplier && object.has("field")) {
            String named = object.get("field").asText();
            if (blocked(named, FieldPermissions.COST, true, path)) {
                object.remove("before"); object.remove("after");
            }
        }
        for (String name : new ArrayList<>(object.propertyNames())) {
            if (blocked(name, permission, supplier, path)
                    || typeName.contains(".techpack.") && COST_TOTALS.contains(name) && !FieldPermissions.has(FieldPermissions.COST)
                    || typeName.contains(".price.") && Set.of("reason", "remark").contains(name) && !FieldPermissions.has(permission)
                    || (name.equals("summary") || name.equals("feeNote")) && typeName.contains(".order.") && !FieldPermissions.has(FieldPermissions.ORDER)) {
                object.remove(name);
            } else {
                Object child = property(source, name);
                String childPermission = Set.of("bom", "bomItems", "materialSnapshot", "materialSnapshots", "materials").contains(name)
                        ? FieldPermissions.COST : permission;
                visit(child == null ? object.get(name) : child, object.get(name), childPermission, supplier, path + "/" + name);
            }
        }
    }

    private static boolean blocked(String name, String permission, boolean supplier, String path) {
        if (name.equals("tagPrice") || name.equals("estTagPrice") || name.contains("吊牌价")) return false;
        if (BANK.contains(name) || supplier && (name.contains("银行") || name.contains("账号") || name.contains("账户") || name.contains("户名")))
            return !FieldPermissions.has(FieldPermissions.BANK);
        if ((supplier && CONTACT.contains(name)) || name.startsWith("supplierContact") || name.startsWith("supplierPhone")
                || name.startsWith("factoryContact") || name.startsWith("factoryPhone")
                || supplier && (name.contains("联系人") || name.contains("电话") || name.contains("手机") || name.contains("微信") || name.contains("地址") || name.contains("传真")))
            return !FieldPermissions.has(FieldPermissions.CONTACT);
        if (COST.contains(name) || name.contains("成本") || name.contains("倍率") || name.equals("其他价格")) return !FieldPermissions.has(FieldPermissions.COST);
        if (MONEY.contains(name) || name.contains("单价") || name.contains("金额") || name.contains("报价") || name.contains("参考价") || name.contains("底价"))
            return !FieldPermissions.has(permission);
        // 物料、BOM 的快照即使包在订单、生产的返回里，也按成本权限保护。
        if (path.contains("/legacyAttrs") && (name.contains("财务") || name.contains("未税"))) return !FieldPermissions.has(FieldPermissions.COST);
        return false;
    }

    private static String domain(String className, String inherited) {
        // 成品发货的单价、金额抄的是订单的（RFC 008 §3.4）
        if (className.contains(".module.order.") || className.contains(".module.fg.")) return FieldPermissions.ORDER;
        if (className.contains(".module.production.")) return FieldPermissions.PRODUCTION;
        if (className.contains(".module.techpack.") || className.contains(".module.inventory.")
                || className.contains(".module.purchase.") || className.contains(".module.basic.")
                || className.contains(".module.style.")) return FieldPermissions.COST;
        return inherited;
    }

    private static Object property(Object source, String name) {
        if (source == null) return null;
        if (source instanceof Map<?, ?> map) return map.get(name);
        if (source instanceof JsonNode node) return node.get(name);
        if (!source.getClass().getName().startsWith("com.lingmao.scm.")) return null;
        Field field = FIELDS.computeIfAbsent(source.getClass(), type -> {
            Map<String, Field> fields = new HashMap<>();
            for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass())
                for (Field f : current.getDeclaredFields()) if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.trySetAccessible())
                    fields.putIfAbsent(f.getName(), f);
            return Map.copyOf(fields);
        }).get(name);
        try { return field == null ? null : field.get(source); }
        catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
    }
}
