package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.dingtalk.DingTalkClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 系统用户 → 钉钉 userid（RFC 019 §4）：按用户档案上的手机号去钉钉查，查到记在 sys_user.dingtalk_user_id，
 * 手机号改了（和 dingtalk_mobile 不一样）重新查。停用、删了、没手机号、钉钉里查不到的发不了
 */
@Component
@RequiredArgsConstructor
class DingTalkRecipients {

    private final JdbcTemplate jdbc;
    private final DingTalkClient client;

    /** 发不了的原因（给管理员看的一句话） */
    static final class NoRecipient extends RuntimeException {
        NoRecipient(String reason) {
            super(reason);
        }
    }

    /** unreachable：删了，或者是工厂账号（RFC 019 §7，工厂账号不推钉钉） */
    private record UserRow(String nickname, String phone, int status, boolean unreachable, String dingtalkUserId, String dingtalkMobile) {
    }

    /** 钉钉 userid；发不了抛 NoRecipient，钉钉报错抛 DingTalkException */
    String resolve(long userId) {
        List<UserRow> rows = jdbc.query("""
                SELECT nickname, phone, status, deleted OR partner_id IS NOT NULL AS unreachable, dingtalk_user_id, dingtalk_mobile
                FROM sys_user WHERE id = ?
                """, (rs, n) -> new UserRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getString(5),
                rs.getString(6)), userId);
        if (rows.isEmpty() || rows.getFirst().unreachable() || rows.getFirst().status() != 1) {
            throw new NoRecipient("收件人已停用、删除，或是工厂账号（工厂账号不推钉钉）");
        }
        UserRow u = rows.getFirst();
        String mobile = mobile(u.phone());
        if (mobile == null) {
            throw new NoRecipient(u.nickname() + " 的账号上没有手机号：在用户管理里补上，钉钉按手机号找人");
        }
        if (u.dingtalkUserId() != null && mobile.equals(u.dingtalkMobile())) {
            return u.dingtalkUserId();
        }
        String found = client.userIdByMobile(mobile);
        if (found == null || found.isBlank()) {
            throw new NoRecipient("钉钉里找不到 " + u.nickname() + " 的手机号：不在这个企业的钉钉里，或者钉钉上登记的是别的号");
        }
        jdbc.update("UPDATE sys_user SET dingtalk_user_id = ?, dingtalk_mobile = ? WHERE id = ?", found, mobile, userId);
        return found;
    }

    /** 去掉空格、横线、+86 前缀；不是 11 位手机号的当没有 */
    static String mobile(String phone) {
        if (phone == null) {
            return null;
        }
        String p = phone.replaceAll("[\\s\\-]", "").replaceFirst("^(\\+?86)(?=1\\d{10}$)", "");
        return p.matches("1\\d{10}") ? p : null;
    }
}
