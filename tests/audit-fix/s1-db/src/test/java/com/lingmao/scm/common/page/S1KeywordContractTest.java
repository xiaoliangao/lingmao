package com.lingmao.scm.common.page;

import com.lingmao.scm.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证用户可见查询语义，不用源码字符串断言代替行为。 */
class S1KeywordContractTest {

    @Test
    void unicodeSpacesFromExcelAndChineseInputProduceExactCodes() {
        assertThat(Keywords.codes("jg001\u00a0JG002\u3000jg001\u2003cp003，CP004\ncp005\u0085cp006\uFEFFcp007"))
                .containsExactly("JG001", "JG002", "CP003", "CP004", "CP005", "CP006", "CP007");
        assertThat(Keywords.codeContains("JG001\u3000JG002")).isNull();
        assertThat(Keywords.codes("\uFEFF\u00a0JG001\u3000\u0085")).isNull();
        assertThat(Keywords.codeContains("\uFEFF\u00a0JG001\u3000\u0085")).isEqualTo("%JG001%");
        assertThat(Keywords.contains("\u00a0\u3000\u0085\uFEFF")).isNull();
    }

    @Test
    void oneHundredUniqueCodesAreAcceptedButTheNextCodeIsRejected() {
        String hundred = IntStream.rangeClosed(1, 100).mapToObj(i -> "JG" + i)
                .collect(Collectors.joining(","));
        assertThat(Keywords.codes(hundred + ",jg1;JG100")).hasSize(100);
        assertThatThrownBy(() -> Keywords.codes(hundred + "\u00a0CP101"))
                .isInstanceOfSatisfying(BizException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(400);
                    assertThat(error.getMessage()).contains("100", "101");
                });
    }

    @Test
    void repeatedOrSingleInputsStillRespectTheWholeRequestBudget() {
        assertThatThrownBy(() -> Keywords.codes("JG1,".repeat(2_049)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> Keywords.codeContains("A".repeat(Keywords.MAX_LENGTH + 1)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> Keywords.contains(" ".repeat(Keywords.MAX_LENGTH + 1)))
                .isInstanceOfSatisfying(BizException.class, error -> assertThat(error.getCode()).isEqualTo(400));
    }

    @Test
    void wildcardCharactersRemainLiteralAndDoNotBecomeArbitrarySearches() {
        assertThat(Keywords.codeContains("jg_%\\1")).isEqualTo("%JG\\_\\%\\\\1%");
        assertThat(Keywords.contains("面料_%\\1")).isEqualTo("%面料\\_\\%\\\\1%");
        assertThat(Keywords.codes("JG_%\\1,CP002")).containsExactly("JG_%\\1", "CP002");
    }
}
