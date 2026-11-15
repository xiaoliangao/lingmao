package com.lingmao.scm.module.basic.service.impl;

import java.util.Locale;

/** 存库前规整文字：去首尾空白，空的当没填；编码转大写 */
final class Texts {

    private Texts() {
    }

    static String trim(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String upper(String text) {
        String trimmed = trim(text);
        return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
