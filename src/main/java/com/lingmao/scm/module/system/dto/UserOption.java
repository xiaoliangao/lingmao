package com.lingmao.scm.module.system.dto;

import com.lingmao.scm.module.system.entity.SysUser;

/** 选人下拉框的一个选项（UserSelect）：只给 id 和名字，登录就能取，所以手机号、状态这些都不给 */
public record UserOption(Long id, String username, String nickname) {

    public static UserOption from(SysUser user) {
        return new UserOption(user.getId(), user.getUsername(), user.getNickname());
    }
}
