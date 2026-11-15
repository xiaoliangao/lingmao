package com.lingmao.scm.module.supplier.service.impl;

/**
 * 银行账号的规整和遮挡（RFC 004 §5、数据库设计 §1.6）：只遮账号，前 4 后 4，中间 ****；开户行、户名不遮。
 */
final class BankAccounts {

    private static final String STARS = "****";

    private BankAccounts() {
    }

    /** 去掉所有空白（"6222 0000 1234 5678" 这样分段写的），空的返回 null */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String account = raw.replaceAll("[\\s\\u3000]+", "");
        return account.isEmpty() ? null : account;
    }

    /**
     * 遮成前 4 后 4：6222021234567890123 → 6222****0123。
     * 8 位以内的整个遮掉：前 4 后 4 加起来就是全号了
     */
    static String mask(String account) {
        if (account == null || account.isEmpty()) {
            return account;
        }
        if (account.length() <= 8) {
            return STARS;
        }
        return account.substring(0, 4) + STARS + account.substring(account.length() - 4);
    }
}
