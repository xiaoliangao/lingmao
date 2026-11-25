package com.lingmao.scm.module.system.dto;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 编一个号要用到的值（RFC 018 §4.9）：按哪个值选规则（大类、物料类型），各段取什么。
 * 值给的是 Supplier，规则里有那一段才去取：没用"设计师编号"的规则，就不要求选设计师、不锁编号。
 * Supplier 自己判断缺了什么就抛 BizException（"自动编号要先选设计师……"）；返回 null 的，由 CodeRuleService 报"这一段没有值"
 */
public final class CodeContext {

    private final String scopeValue;
    private final Map<CodeSegment.Type, Supplier<String>> values = new EnumMap<>(CodeSegment.Type.class);

    private CodeContext(String scopeValue) {
        this.scopeValue = scopeValue;
    }

    /** scopeValue：款号是大类，物料编码是物料类型，供应商编码是 null */
    public static CodeContext of(String scopeValue) {
        return new CodeContext(scopeValue);
    }

    public CodeContext with(CodeSegment.Type type, Supplier<String> value) {
        values.put(type, value);
        return this;
    }

    public String scopeValue() {
        return scopeValue;
    }

    /** 这一段的值；没给 Supplier 的返回 null */
    public Supplier<String> supplier(CodeSegment.Type type) {
        return values.get(type);
    }
}
