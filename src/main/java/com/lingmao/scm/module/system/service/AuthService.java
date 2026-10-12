package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.LoginReq;

public interface AuthService {

    /** 登录成功返回 token；用户名或密码不对抛 BizException */
    String login(LoginReq req);
}