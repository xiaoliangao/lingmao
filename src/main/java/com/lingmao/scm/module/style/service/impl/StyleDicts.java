package com.lingmao.scm.module.style.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 款式上存字典 value 的十列（RFC 005 §6）：新选的值只认启用的选项；修改时没动的旧值，选项后来停用了也照样留着。
 * 不合法返回 400，写法同 basic 的 DictChecks（包内私有，style 不能用，抄一份）
 */
@Component
@RequiredArgsConstructor
class StyleDicts {

    static final String STYLE_CLASS = "style_class";
    static final String BRAND = "brand";
    static final String SEASON = "season";
    static final String WAVE = "wave";
    static final String STYLE_TAG = "style_tag";
    static final String SERIES = "series";
    static final String GENDER = "gender";
    static final String SOURCE = "style_source";
    static final String EXEC_STANDARD = "exec_standard";
    static final String SAFETY_CATEGORY = "safety_category";

    /** 来源：自主开发（导入时空的填它）、客户来样（TRADE 关时拒收）。字典内置项，按 value 认 */
    static final String OWN = "OWN";
    static final String CUSTOMER_SAMPLE = "CUSTOMER_SAMPLE";

    private final DictDataService dictDataService;

    /** 全部选项，停用的也在 */
    List<DictOption> options(String typeCode) {
        return dictDataService.listByType(typeCode);
    }

    /**
     * @param value    要存的值，空的不查（必填由 DTO 管）
     * @param previous 修改前的值；新建传 null
     * @param what     列的中文名，拼进提示
     */
    void require(String typeCode, String value, String previous, String what) {
        if (value == null || Objects.equals(value, previous)) {
            return;
        }
        boolean ok = options(typeCode).stream().anyMatch(o -> o.enabled() && o.value().equals(value));
        if (!ok) {
            throw new BizException(400, what + "「" + value + "」不在字典里，或者已经停用了");
        }
    }

    /** 名称里含 keyword 的选项的 value，不分大小写，停用的也算：选款式时按品牌名称搜 */
    List<String> valuesLabelled(String typeCode, String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT);
        return options(typeCode).stream().filter(o -> o.label().toLowerCase(Locale.ROOT).contains(k)).map(DictOption::value).toList();
    }

    /** value → label，停用的也翻；字典里没有的（被删了）原样 */
    Map<String, String> labels(String typeCode) {
        return options(typeCode).stream().collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }
}
