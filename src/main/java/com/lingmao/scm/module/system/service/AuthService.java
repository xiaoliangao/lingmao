package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoV0;

import java.util.List;

public interface AuthService {

    /** 登录成功返回 token；用户名或密码不对抛 BizException */
    String login(LoginReq req);

    UserInfoV0 me(Long userId);

    void logout(String jti);
}