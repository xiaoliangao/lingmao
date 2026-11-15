package com.lingmao.scm.module.supplier.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一社会信用代码（PRD §6.8.1、RFC 004 §4.1）。用的号都是编的：前 17 位随便写，校验码按国标另外手算的，
 * 不是哪家真企业的号
 */
class CreditCodeTest {

    /** 手算过校验码的假号 */
    static final String VALID = "91000000MA0000001R";

    /** 规则：18 位、字符集对、最后一位校验码对 → 没问题 */
    @Test
    void problem_validCode_null() {
        assertThat(CreditCode.problem(VALID)).isNull();
        assertThat(CreditCode.problem("91000000MA0000002W")).isNull();
        assertThat(CreditCode.problem("91000000MA00000030")).isNull();
    }

    /** 规则：改了最后一位（抄错一位）→ 校验码不对 */
    @Test
    void problem_wrongCheckChar_rejected() {
        assertThat(CreditCode.problem("91000000MA0000001W")).contains("校验码不对");
    }

    /** 规则：位数不对、有 I O Z S V 这些不用的字母，各说各的 */
    @Test
    void problem_lengthOrCharset_rejected() {
        assertThat(CreditCode.problem("91000000MA000000")).contains("要 18 位");
        assertThat(CreditCode.problem("91000000MA0000001O")).contains("第 18 位").contains("不含 I、O、Z、S、V");
    }

    /** 规则：小写、中间带空格（含全角空格）的，规整以后能过；全是空白的当没填 */
    @Test
    void normalize_lowercaseAndSpaces_passAfterNormalize() {
        String normalized = CreditCode.normalize(" 9100 0000　ma00 00001r ");

        assertThat(normalized).isEqualTo(VALID);
        assertThat(CreditCode.problem(normalized)).isNull();
        assertThat(CreditCode.normalize("  ")).isNull();
    }
}
