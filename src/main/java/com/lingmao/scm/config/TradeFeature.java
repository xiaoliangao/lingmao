package com.lingmao.scm.config;

import com.lingmao.scm.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 贸易代工（TRADE）开关（架构 §14.4、RFC 003 §3.3）：配置项 scm.trade.enabled，部署配置里是 SCM_TRADE_ENABLED，默认关。
 * 只在这里读一次，别处都问它；各域和 system 都能用 config。
 * <p>
 * 关着时，代工专用的值（"保留值"）一律不收：保存时返回 400，字典选项接口把它们滤掉。保留值的清单也只写在这里：
 * 以后加订单类型、BOM 来源、入库类型、物权的保留值，各自的 RFC 往这里加。
 * 前端一期按"关"做，不读开关（/api/auth/me 先不带它，补客户界面时再加）。
 */
@Component
public class TradeFeature {

    /** 物料的默认来源"客供"（bas_material.default_source），BOM 的物料来源以后也用这个值 */
    public static final String CUSTOMER_SUPPLIED = "CUSTOMER_SUPPLIED";

    /** 代工专用的字典项：字典类型 → value。管理后台的字典页照常列出它们，标"代工" */
    private static final Map<String, Set<String>> RESERVED_DICT = Map.of(
            "style_source", Set.of("CUSTOMER_SAMPLE"),
            "order_type", Set.of("CUSTOMER"));

    private final boolean enabled;

    public TradeFeature(@Value("${scm.trade.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 这个字典项是不是代工专用的（不管开关开没开）：管理后台标"代工"用 */
    public boolean isReservedDict(String typeCode, String value) {
        return RESERVED_DICT.getOrDefault(typeCode, Set.of()).contains(value);
    }

    /** 开关关着时，从字典选项里去掉代工专用的；开着原样返回 */
    public <T> List<T> filterDict(String typeCode, List<T> options, Function<T, String> valueOf) {
        if (enabled || !RESERVED_DICT.containsKey(typeCode)) {
            return options;
        }
        return options.stream().filter(option -> !isReservedDict(typeCode, valueOf.apply(option))).toList();
    }

    /** 开关关着时，用到了代工专用的值就返回 400。what 说清是哪个值，如"物料来源「客供」" */
    public void rejectIfOff(boolean usesTrade, String what) {
        if (usesTrade && !enabled) {
            throw new BizException(400, what + "是贸易代工用的，系统没有打开代工功能");
        }
    }
}
