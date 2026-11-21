package com.lingmao.scm.common.doc;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 操作记录的改前改后（RFC 016 §3）：只记变了的字段，按显示的文字比 */
class FieldDiffTest {

    record Doc(String name, String type, BigDecimal price, Boolean tag, List<String> tags, String remark) {
    }

    private static final Map<String, String> TYPES = Map.of("FABRIC", "面料", "TRIM", "辅料");

    private static List<FieldChange> diff(Doc before, Doc after) {
        return FieldDiff.of(before, after)
                .field("name", "名称", Doc::name)
                .field("type", "物料类型", Doc::type, TYPES::get)
                .masked("price", "参考单价", Doc::price, "techpack:cost:query")
                .field("tag", "带吊牌", Doc::tag)
                .field("tags", "标签", Doc::tags)
                .field("remark", "备注", Doc::remark)
                .list();
    }

    /** 规则：只列变了的；字典值翻成中文；要权限的字段带上权限名，读的时候再遮 */
    @Test
    void onlyChangedFields_withDictLabelsAndPermission() {
        Doc before = new Doc("棉布", "FABRIC", new BigDecimal("12.5"), true, List.of("A"), "旧");
        Doc after = new Doc("棉布", "TRIM", new BigDecimal("13"), true, List.of("A"), "旧");

        assertThat(diff(before, after)).containsExactly(
                new FieldChange("type", "物料类型", "面料", "辅料", null),
                new FieldChange("price", "参考单价", "12.5", "13", "techpack:cost:query"));
    }

    /** 规则：12.50 和 12.5 一样、空串和 null 一样、空列表和 null 一样，都不算改了 */
    @Test
    void sameDisplay_isNotAChange() {
        Doc before = new Doc("棉布", null, new BigDecimal("12.50"), null, List.of(), "");
        Doc after = new Doc(" 棉布 ", null, new BigDecimal("12.5"), null, null, null);

        assertThat(diff(before, after)).isEmpty();
    }

    /** 规则：从空填上、清成空，空的一边是 null；字典里没有的值显示原值；是 / 否、几个值用顿号 */
    @Test
    void nullsAndFallbacks() {
        Doc before = new Doc("棉布", "OLD", null, false, List.of("A", "B"), "旧");
        Doc after = new Doc("棉布", "FABRIC", BigDecimal.ZERO, true, List.of("A"), null);

        assertThat(diff(before, after)).containsExactly(
                new FieldChange("type", "物料类型", "OLD", "面料", null),
                new FieldChange("price", "参考单价", null, "0", "techpack:cost:query"),
                new FieldChange("tag", "带吊牌", "否", "是", null),
                new FieldChange("tags", "标签", "A、B", "A", null),
                new FieldChange("remark", "备注", "旧", null, null));
    }
}
