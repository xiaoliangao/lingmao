package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;


public interface AuthService {

    /**
     * 登录成功返回 token；用户名或密码不对、账号停用都抛 BizException
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
}