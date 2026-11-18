package com.lingmao.scm.common.page;

import com.lingmao.scm.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeywordsTest {

    /** 空白（Excel 一列粘进来是换行）、半角全角逗号、顿号、分号都算分隔；大写、去重、按原来的顺序 */
    @Test
    void splitsOnAnySeparator() {
        assertThat(Keywords.codes("jg001\nJG002\r\njg001\tcp003，cp004、cp005; cp006；")).containsExactly(
                "JG001", "JG002", "CP003", "CP004", "CP005", "CP006");
        // 只有一个编码、后面多打了个逗号：照样精确查这一个
        assertThat(Keywords.codes("JG001,")).containsExactly("JG001");
    }

    /** 没有分隔符就是一个词，只按包含查 */
    @Test
    void singleWordIsNotCodes() {
        assertThat(Keywords.codes("全棉")).isNull();
        assertThat(Keywords.codes("  JG001  ")).isNull();
        assertThat(Keywords.codes(null)).isNull();
    }

    /** 一次最多 100 个：再多 GET 地址太长 */
    @Test
    void tooManyCodes() {
        String keyword = IntStream.rangeClosed(1, Keywords.MAX_CODES + 1).mapToObj(i -> "JG" + i).collect(Collectors.joining(" "));
        assertThatThrownBy(() -> Keywords.codes(keyword)).isInstanceOfSatisfying(BizException.class, e -> {
            assertThat(e.getCode()).isEqualTo(400);
            assertThat(e.getMessage()).contains("一次最多查 100 个，现在 101 个");
        });
        assertThat(Keywords.codes(keyword.substring(0, keyword.lastIndexOf(' ')))).hasSize(Keywords.MAX_CODES);
    }
}
