package com.lingmao.scm.module.system.dto;

/**
 * 选人下拉框的一个选项（UserSelect）：只给 id、名字和部门，登录就能取，所以手机号、状态这些都不给。
 *
 * @param depts 在哪几个部门（本级名字，"、"隔开），没有是 null：同名、花名相近的人好分（RFC 018 §1.4）
 */
public record UserOption(Long id, String username, String nickname, String depts) {
}
