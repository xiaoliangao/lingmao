package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.AssistantTokenVO;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.dto.AuthTokens;


public interface AuthService {

    /**
     * 登录成功返回 token；用户名或密码不对、账号停用、输错太多次被锁住都抛 BizException。
     * 同一个用户名连续输错 5 次锁 15 分钟，锁住期间密码对了也不让进
     */
    AuthTokens login(LoginReq req);
    AuthTokens refresh(String refreshToken, String browserToken);
    void revokeRefresh(String refreshToken, String browserToken);

    /**
     * 当前登录用户的信息：基本信息、角色、权限标识、侧边栏菜单、默认主体
     */
    UserInfoVO me(Long userId);

    /**
     * 用当前登录换一张给智能助手用的短期只读令牌（RFC 017 §3.1）：30 分钟、不能刷新，
     * 和登录令牌一样登记用户版本，改密、停用、删除时一起失效；换它的那张登录令牌退出时也一起失效。
     * AI 功能关着、调用的本身就是助手令牌，抛 AssistantDenied（403）；换的这一刻登录已经失效抛 AuthRejected（401）
     */
    AssistantTokenVO assistantToken(LoginUser caller);

    /**
     * 退出登录：让这张 token 立即失效
     */
    void logout(String jti);

    /**
     * 自己改密码：旧密码不对抛 BizException（1000）；提交后所有设备的旧访问令牌和刷新会话立即失效。
     */
    void changePassword(Long userId, String oldPassword, String newPassword);
}
