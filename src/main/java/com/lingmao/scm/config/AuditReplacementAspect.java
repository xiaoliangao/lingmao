package com.lingmao.scm.config;

import com.lingmao.scm.common.log.AuditContext;
import com.lingmao.scm.module.purchase.dto.PurchaseSaveReq;
import com.lingmao.scm.module.techpack.dto.TechPackSaveReq;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Preserves the explicit old identity before the existing save routine replaces rows.
 * These hints contain IDs only. The database accepts a link only to a same-root row
 * actually deleted in this transaction; values and matching by business keys are forbidden.
 */
@Aspect @Component @Order(0)
public class AuditReplacementAspect {
    private final JsonMapper json;
    public AuditReplacementAspect(JsonMapper json) { this.json = json; }

    @Around("execution(* com.lingmao.scm.module.techpack.service.impl.TechPackServiceImpl.save(..)) && args(id,request)")
    public Object techPack(ProceedingJoinPoint call, long id, TechPackSaveReq request) throws Throwable {
        Map<String, Object> tables = new LinkedHashMap<>();
        tables.put("tp_bom_item", identities(request.getBomItems(), row -> row.getId()));
        tables.put("tp_process", identities(request.getProcesses(), row -> row.getId()));
        return proceed(call, "tp_tech_pack", id, tables);
    }

    @Around("execution(* com.lingmao.scm.module.purchase.service.impl.PurchaseOrderServiceImpl.update(..)) && args(id,request)")
    public Object purchase(ProceedingJoinPoint call, long id, PurchaseSaveReq request) throws Throwable {
        return proceed(call, "pur_order", id, Map.of("pur_order_item", identities(request.getItems(), row -> row.getId())));
    }

    private Object proceed(ProceedingJoinPoint call, String table, long id, Map<String, Object> tables) throws Throwable {
        try (var ignored = AuditContext.replacements(json.writeValueAsString(Map.of(
                "rootTable", table, "rootId", Long.toString(id), "tables", tables)))) {
            return call.proceed();
        }
    }

    private static <T> Map<String, String> identities(List<T> rows, Function<T, Long> identity) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rows != null) for (int i = 0; i < rows.size(); i++) {
            Long id = rows.get(i) == null ? null : identity.apply(rows.get(i));
            if (id != null && id > 0) result.put(Integer.toString(i + 1), id.toString());
        }
        return result;
    }
}
