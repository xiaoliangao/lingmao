package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;


public interface AuthService {

    /**
     * 登录成功返回 token；用户名或密码不对、账号停用、输错太多次被锁住都抛 BizException。
     * 同一个用户名连续输错 5 次锁 15 分钟，锁住期间密码对了也不让进
     */
    String login(LoginReq req);

    /**
     * 当前登录用户的信息：基本信息、角色、权限标识、侧边栏菜单
     */
    UserInfoVO me(Long userId);

    /**
     * 退出登录：让这张 token 立即失效
     */
    void logout(String jti);

    /**
     * 自己改密码：旧密码不对抛 BizException（1000）。别的设备上已经登录的不踢下线，token 最长 2 小时自己过期
     */
    void changePassword(Long userId, String oldPassword, String newPassword);
}