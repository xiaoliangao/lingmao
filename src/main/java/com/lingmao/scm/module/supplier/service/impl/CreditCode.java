package com.lingmao.scm.module.supplier.service.impl;

import java.util.Locale;

/**
 * 统一社会信用代码（GB 32100-2015，RFC 004 §4.1）：18 位，字符集是数字和 A–Y 里去掉 I、O、Z、S、V 的 31 个字符，
 * 第 18 位是前 17 位的加权校验码。
 * <p>
 * 校验码：前 17 位每个字符的值（在字符集里的位置，0–30）乘对应的权重求和，C18 = 31 − 和 mod 31，算出 31 记作 0，
 * 再取字符集里这个位置的字符。权重是 3 的 i 次方 mod 31（国标表 2 列出的那一串）。
 * <p>
 * 只查格式和校验码，不查前两位的登记管理部门、3–8 位的行政区划：这两段国标有表，但会增补，查死了新代码过不去；
 * 校验码已经能拦住绝大多数抄错的号。
 */
public final class CreditCode {

    /** 字符集：值就是下标 */
    static final String CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    private static final int[] WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    public static final int LENGTH = 18;

    private CreditCode() {
    }

    /** 存之前规整：去掉所有空白（全角空格也算）、转大写。空的返回 null */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String code = raw.replaceAll("[\\s\\u3000]+", "").toUpperCase(Locale.ROOT);
        return code.isEmpty() ? null : code;
    }

    /** 规整过的号有什么问题，给用户看的一句话；没问题（或者是空的）返回 null */
    public static String problem(String code) {
        if (code == null) {
            return null;
        }
        if (code.length() != LENGTH) {
            return "统一社会信用代码要 18 位，「" + code + "」是 " + code.length() + " 位";
        }
        for (int i = 0; i < LENGTH; i++) {
            if (CHARSET.indexOf(code.charAt(i)) < 0) {
                return "统一社会信用代码「" + code + "」第 " + (i + 1) + " 位的「" + code.charAt(i)
                        + "」不对：只能是数字和大写字母，不含 I、O、Z、S、V";
            }
        }
        if (code.charAt(LENGTH - 1) != checkChar(code)) {
            return "统一社会信用代码「" + code + "」的最后一位校验码不对，可能抄错了一位，请对着营业执照再核一遍";
        }
        return null;
    }

    /** 前 17 位算出来的校验码。code 至少 17 位，字符都在字符集里 */
    static char checkChar(String code) {
        int sum = 0;
        for (int i = 0; i < LENGTH - 1; i++) {
            sum += CHARSET.indexOf(code.charAt(i)) * WEIGHTS[i];
        }
        int check = 31 - sum % 31;
        return CHARSET.charAt(check == 31 ? 0 : check);
    }
}
