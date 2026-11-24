package com.lingmao.scm.security;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.reflection.SystemMetaObject;
import java.util.regex.Pattern;

/** Read the actual bound SQL LIMIT, including a limited subquery used for scalar preflight. */
final class ExportSqlLimits {
    private ExportSqlLimits() { }
    static Long limit(BoundSql bound) {
        var match = Pattern.compile("(?i)\\blimit\\s+(\\?|[0-9]+)").matcher(bound.getSql());
        String token = null; int start = -1;
        while (match.find()) { token = match.group(1); start = match.start(1); }
        if (token == null) return null;
        if (!token.equals("?")) return Long.valueOf(token);
        int parameter = 0;
        for (int i = 0; i < start; i++) if (bound.getSql().charAt(i) == '?') parameter++;
        String property = bound.getParameterMappings().get(parameter).getProperty();
        Object value = bound.hasAdditionalParameter(property) ? bound.getAdditionalParameter(property)
                : SystemMetaObject.forObject(bound.getParameterObject()).getValue(property);
        return value instanceof Number number ? number.longValue() : null;
    }
}
