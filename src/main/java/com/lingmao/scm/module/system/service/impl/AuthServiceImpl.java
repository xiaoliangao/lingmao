package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.AssistantTokenVO;
import com.lingmao.scm.module.system.dto.AuthTokens;
import com.lingmao.scm.module.system.dto.FeaturesVO;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.AuthService;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import com.lingmao.scm.module.system.service.FactoryDirectory;
import com.lingmao.scm.module.system.dto.FactoryBrief;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginFailCounter;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.RefreshSessionService;
import com.lingmao.scm.security.AssistantDenied;
import com.lingmao.scm.security.AuthRejected;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginSessionStore loginSessionStore;
    private final PermissionService permissionService;
    private final LoginFailCounter loginFailCounter;
    private final DefaultCompanyProvider defaultCompanyProvider;
    private final FactoryDirectory factoryDirectory;
    private final RefreshSessionService refreshSessions;
    private final AiSwitchService aiSwitchService;
    private final Clock clock;

    /** 助手令牌的有效期（RFC 017 §3.1）：不能刷新，过期了浏览器再换一张 */
    static final Duration ASSISTANT_TTL = Duration.ofMinutes(30);

    @Override
    @Transactional
    public AuthTokens login(LoginReq req) {
        String username = req.getUsername();
        // 锁住了就不再比对密码：不然锁定期间照样能一直试，锁就白加了
        if (loginFailCounter.isLocked(username)) {
            throw new BizException("密码输错次数太多，账号已锁定，请 " + loginFailCounter.minutesLeft(username) + " 分钟后再试");
        }
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username));
        if (user == null || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            // 用户名不存在也照样计数、照样提示还能试几次：提示不一样的话，别人就能试出哪些用户名是真的
            int left = loginFailCounter.recordFailure(username);
            throw new BizException(left > 0
                    ? "用户名或密码错误，还能试 " + left + " 次"
                    : "用户名或密码错误，账号已锁定，请 " + loginFailCounter.minutesLeft(username) + " 分钟后再试");
        }
        if (user.getStatus() != 1) {
            throw new BizException("账号已停用");
        }
        loginFailCounter.clear(username);
        String jti = UUID.randomUUID().toString();
        loginSessionStore.save(jti, user.getId(), user.getPassword());
        return refreshSessions.issue(jwtUtil.createToken(user.getId(), jti));
    }

    @Override
    public AuthTokens refresh(String refreshToken, String browserToken) { return refreshSessions.rotate(refreshToken, browserToken); }

    @Override
    public void revokeRefresh(String refreshToken, String browserToken) { refreshSessions.revoke(refreshToken, browserToken); }

    @Override
    public UserInfoVO me(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }
        boolean mustChange = Boolean.TRUE.equals(user.getPwdMustChange());
        // 工厂账号（RFC 019 §7）：没有角色、菜单、权限码，前端看 factory 只开手机报工页
        FactoryBrief factory = user.getPartnerId() == null ? null : factoryDirectory.find(user.getPartnerId());
        boolean bare = mustChange || user.getPartnerId() != null;
        return new UserInfoVO(user.getId(), user.getUsername(), user.getNickname(),
                user.getPartnerId() != null ? java.util.List.of() : permissionService.loadRoleCodes(userId),
                bare ? java.util.Set.of() : permissionService.loadPermissions(userId),
                bare ? java.util.List.of() : permissionService.loadMenuTree(userId),
                user.getPartnerId() != null ? null : defaultCompanyProvider.resolve(user.getDefaultCompanyId()),
                new FeaturesVO(aiSwitchService.enabled()),
                com.lingmao.scm.security.DataScope.current(false).isAll(),
                mustChange,
                factory);
    }

    @Override
    @Transactional
    public AssistantTokenVO assistantToken(LoginUser caller) {
        if (caller.scope() != null) {
            throw new AssistantDenied("助手令牌不能再换令牌");
        }
        if (!aiSwitchService.enabled()) {
            throw new AssistantDenied("AI 功能没有打开");
        }
        String jti = UUID.randomUUID().toString();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ASSISTANT_TTL);
        loginSessionStore.saveScoped(jti, caller.userId(), LoginUser.SCOPE_ASSISTANT, caller.jti(), expiresAt);
        String token = jwtUtil.createScopedToken(caller.userId(), jti, LoginUser.SCOPE_ASSISTANT, now, ASSISTANT_TTL);
        return new AssistantTokenVO(token, OffsetDateTime.ofInstant(expiresAt, clock.getZone()));
    }

    @Override
    @Transactional
    public void logout(String jti) {
        loginSessionStore.remove(jti);
    }

    @Override
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + userId);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException("旧密码不对");
        }
        if (sysUserMapper.changePasswordIfCurrent(userId, user.getPassword(), passwordEncoder.encode(newPassword)) != 1)
            throw new AuthRejected();
    }
}
