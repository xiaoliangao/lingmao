package com.lingmao.scm.module.supplier.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 供应商用到的字典（RFC 003 §18 预置数据）：值合不合法、值翻成文字。字典走 Redis 缓存（DictDataService.listByType）。
 * <p>
 * 新填的值只认启用的；原来就是这个值（选项后来停用了）照样能原样保存（RFC 001 §18：前端 DictSelect 把它标"已停用"，不换也能存）
 */
@Component
@RequiredArgsConstructor
class SupplierDicts {

    static final String SUPPLIER_CATEGORY = "supplier_category";
    static final String SETTLE_METHOD = "settle_method";
    static final String INVOICE_TYPE = "invoice_type";
    static final String CERT_TYPE = "cert_type";

    /** 供应商分类里会建工厂仓的两类（PRD §6.1.6），字典内置项，按 value 认 */
    static final String GARMENT = "GARMENT";
    static final String PROCESSOR = "PROCESSOR";

    /** 证照类型：营业执照，申请准入时必须有（字典内置项） */
    static final String LICENSE = "LICENSE";

    private final DictDataService dictDataService;

    List<DictOption> options(String type) {
        return dictDataService.listByType(type);
    }

    /** value 是空，或者是启用的选项，或者和 current 一样：放行。否则 1000 "label「value」不在选项里" */
    void check(String type, String value, String current, String label) {
        if (value == null || value.equals(current)) {
            return;
        }
        boolean ok = options(type).stream().anyMatch(o -> o.enabled() && o.value().equals(value));
        if (!ok) {
            throw new BizException(label + "「" + value + "」不在选项里，或者已经停用了，请重新选");
        }
    }

    /** 一组值逐个查，current 里有的放行（原来就选着的分类停用了，照样能留着） */
    void checkAll(String type, Collection<String> values, Collection<String> current, String label) {
        for (String value : values) {
            check(type, value, current.contains(value) ? value : null, label);
        }
    }

    /** 值 → 文字；字典里没有的（被删了）返回值本身 */
    String labelOf(String type, String value) {
        if (value == null) {
            return null;
        }
        return options(type).stream().filter(o -> o.value().equals(value)).map(DictOption::label)
                .findFirst().orElse(value);
    }

    /** 按字典里的顺序排（分类显示成"面料商、辅料商"，不按字母） */
    List<String> sorted(String type, Collection<String> values) {
        List<String> order = options(type).stream().map(DictOption::value).toList();
        return values.stream().filter(Objects::nonNull).distinct()
                .sorted(Comparator.comparingInt((String v) -> {
                    int i = order.indexOf(v);
                    return i < 0 ? Integer.MAX_VALUE : i;
                }).thenComparing(Comparator.naturalOrder()))
                .toList();
    }
}
