package com.lingmao.scm.module.biz.dto;

/**
 * 操作记录里改了的一个字段（RFC 016 §3）：before、after 是当时显示的文字，空是 null。
 * hidden = true：这个字段要权限才能看值（价格、银行账号），看的人没有权限，before、after 都给 null，页面写"已修改"
 */
public record FieldChangeVO(String field, String label, String before, String after, boolean hidden) {
}
