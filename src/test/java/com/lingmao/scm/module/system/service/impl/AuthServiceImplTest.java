package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.CompanyBrief;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.AssistantTokenVO;
import com.lingmao.scm.module.system.dto.AuthTokens;
import com.lingmao.scm.module.system.dto.FeaturesVO;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.DefaultCompanyProvider;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.AssistantDenied;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginUser;
import com.lingmao.scm.security.LoginFailCounter;
import com.lingmao.scm.security.LoginSessionStore;
import com.lingmao.scm.security.RefreshSessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private LoginSessionStore loginSessionStore;

    @Mock
    private RefreshSessionService refreshSessions;

    @Mock
    private PermissionService permissionService;

    @Mock
    private LoginFailCounter loginFailCounter;

    @Mock
    private DefaultCompanyProvider defaultCompanyProvider;

    @Mock
    private AiSwitchService aiSwitchService;

    @Mock
    private Clock clock;

    @InjectMocks
    private AuthServiceImpl authService;

    /**
     * 规则：用户名不存在，和密码错一样计数、一样提示还能试几次（不暴露用户名存不存在），不签发 token
     */
    @Test
    void login_userNotFound_throwsAndNoToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);
        when(loginFailCounter.recordFailure("nobody")).thenReturn(4);

        assertThatThrownBy(() -> authService.login(req("nobody", "123456")))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码错误，还能试 4 次");
        verify(jwtUtil, never()).createToken(any(),any());
    }

    /**
     * 规则：密码错，记一次失败，提示还能试几次，不签发 token
     */
    @Test
    void login_wrongPassword_throwsAndNoToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        when(loginFailCounter.recordFailure("admin")).thenReturn(4);

        assertThatThrownBy(() -> authService.login(req("admin", "wrong")))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码错误，还能试 4 次");
        verify(jwtUtil, never()).createToken(any(),any());
    }

    /**
     * 规则：连续输错 5 次锁住；第 6 次密码对了也进不去，而且根本不去比对密码、不签发 token
     */
    @Test
    void login_lockedAfterFiveFailures_rightPasswordRejected() {
        when(loginFailCounter.isLocked("admin")).thenReturn(false, false, false, false, false, true);
        when(loginFailCounter.recordFailure("admin")).thenReturn(4, 3, 2, 1, 0);
        when(loginFailCounter.minutesLeft("admin")).thenReturn(15L);
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        for (int left = 4; left >= 1; left--) {
            assertThatThrownBy(() -> authService.login(req("admin", "wrong")))
                    .hasMessage("用户名或密码错误，还能试 " + left + " 次");
        }
        assertThatThrownBy(() -> authService.login(req("admin", "wrong")))
                .hasMessage("用户名或密码错误，账号已锁定，请 15 分钟后再试");
        assertThatThrownBy(() -> authService.login(req("admin", "right")))
                .isInstanceOf(BizException.class)
                .hasMessage("密码输错次数太多，账号已锁定，请 15 分钟后再试");

        verify(passwordEncoder, never()).matches(eq("right"), anyString());
        verify(jwtUtil, never()).createToken(any(), any());
    }

    /**
     * 规则：登录成功，失败计数清零
     */
    @Test
    void login_ok_clearsFailureCount() {
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("right", "hashed")).thenReturn(true);

        authService.login(req("admin", "right"));

        verify(loginFailCounter).clear("admin");
    }

    /**
     * 规则：停用的账号，密码对也不能登录，不签发 token
     */
    @Test
    void login_disabled_throwsAndNoToken() {
        SysUser user = admin();                                             // ① 先拿一个启用的 admin
        user.setStatus(0);                                                  //    再改成停用
        when(sysUserMapper.selectOne(any())).thenReturn(user);              // ② 库里查出来的是这个停用的用户
        when(passwordEncoder.matches("right", "hashed")).thenReturn(true);  // ③ 规定：密码是对的

        assertThatThrownBy(() -> authService.login(req("admin", "right")))  // ④ 用对的密码去登录
                .isInstanceOf(BizException.class)
                .hasMessage("账号已停用");                                    // ⑤ 期望被拒，提示是需求原文
        verify(jwtUtil, never()).createToken(any(),any());                        // ⑥ 没签发 token
    }

    /**
     * 规则：停用的账号密码错，提示和普通密码错一样，不暴露停用
     */
    @Test
    void login_disabledWrongPassword_sameMessage() {
        SysUser user = admin();
        user.setStatus(0);
        when(sysUserMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        when(loginFailCounter.recordFailure("admin")).thenReturn(4);

        assertThatThrownBy(() -> authService.login(req("admin", "wrong")))  // ④ 用错的密码去登录
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码错误，还能试 4 次");                         // ⑤ 和普通的密码错一样
        verify(jwtUtil, never()).createToken(any(),any());
    }

    /**
     * 规则：用户名和密码都对，返回给这个用户签发的 token
     */
    @Test
    void login_ok_returnsToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("right", "hashed")).thenReturn(true);
        when(jwtUtil.createToken(eq(1L),anyString())).thenReturn("token-for-1");
        AuthTokens issued = new AuthTokens("token-for-1", "synthetic-refresh", "synthetic-browser", 604800);
        when(refreshSessions.issue("token-for-1")).thenReturn(issued);

        AuthTokens tokens = authService.login(req("admin", "right"));

        assertThat(tokens).isEqualTo(issued);
        ArgumentCaptor<String> jti = ArgumentCaptor.forClass(String.class);
        verify(loginSessionStore).save(jti.capture(),eq(1L),eq("hashed"));
        verify(jwtUtil).createToken(1L, jti.getValue());
        verify(refreshSessions).issue("token-for-1");
    }

    /** 规则：不带密码；默认主体按用户上设的默认主体问 basic（DefaultCompanyProvider），新建单据时主体默认填它 */
    @Test
    void me_returnsUserInfoWithoutPassword() {
        SysUser user = admin();
        user.setDefaultCompanyId(3L);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("system:user:list"));
        when(permissionService.loadMenuTree(1L)).thenReturn(List.of());
        when(defaultCompanyProvider.resolve(3L)).thenReturn(new CompanyBrief(3L, "测试主体"));
        when(aiSwitchService.enabled()).thenReturn(true);

        UserInfoVO info = authService.me(1L);

        assertThat(info).isEqualTo(new UserInfoVO(1L, "admin", null,
                List.of("admin"), Set.of("system:user:list"), List.of(), new CompanyBrief(3L, "测试主体"), new FeaturesVO(true), false, false));
    }

    /** 规则（RFC 018 §2）：要先改密码的，me 不给权限和菜单，带上 mustChangePassword，前端据此只放改密码页 */
    @Test
    void me_mustChangePassword_noPermsNoMenus() {
        SysUser user = admin();
        user.setPwdMustChange(true);
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));

        UserInfoVO info = authService.me(1L);

        assertThat(info.mustChangePassword()).isTrue();
        assertThat(info.perms()).isEmpty();
        assertThat(info.menus()).isEmpty();
        verify(permissionService, never()).loadPermissions(1L);
        verify(permissionService, never()).loadMenuTree(1L);
    }

    @Test
    void logout_removesSession(){
        authService.logout("jti-1");

        verify(loginSessionStore).remove("jti-1");
    }

    /**
     * 规则：自己改密码，旧密码不对就拒绝（1000），不改库
     */
    @Test
    void changePassword_wrongOldPassword_throwsAndNotUpdate() {
        when(sysUserMapper.selectById(1L)).thenReturn(admin());
        when(passwordEncoder.matches("wrong-old", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(1L, "wrong-old", "new-pass-123"))
                .isInstanceOf(BizException.class)
                .hasMessage("旧密码不对")
                .extracting("code").isEqualTo(1000);
        verify(sysUserMapper, never()).updatePassword(anyLong(), anyString());
        verify(sysUserMapper, never()).changePasswordIfCurrent(anyLong(), anyString(), anyString());
    }

    /**
     * 规则：旧密码对，存进库的是新密码加密后的值。密码列标了 NEVER，走"改密码"这个动作（条件更新），不走 updateById
     */
    @Test
    void changePassword_ok_savesEncodedNewPassword() {
        when(sysUserMapper.selectById(1L)).thenReturn(admin());
        when(passwordEncoder.matches("right-old", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("encoded-new");
        when(sysUserMapper.changePasswordIfCurrent(1L, "hashed", "encoded-new")).thenReturn(1);

        authService.changePassword(1L, "right-old", "new-pass-123");

        verify(sysUserMapper).changePasswordIfCurrent(1L, "hashed", "encoded-new");
        verify(sysUserMapper, never()).updatePassword(anyLong(), anyString());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    private SysUser admin() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("hashed");
        user.setStatus(1);
        return user;
    }

    // ------------------------------------------------------------ 助手令牌（RFC 017 §3.1）

    /** 规则：AI 功能关着 → 换不到助手令牌（403），什么都不登记 */
    @Test
    void assistantToken_aiOff_denied() {
        when(aiSwitchService.enabled()).thenReturn(false);

        assertThatThrownBy(() -> authService.assistantToken(new LoginUser(1L, "login-jti")))
                .isInstanceOf(AssistantDenied.class).hasMessageContaining("AI 功能没有打开");
        verify(loginSessionStore, never()).saveScoped(any(), anyLong(), any(), any(), any());
    }

    /** 规则：拿助手令牌再换令牌不行（续命） */
    @Test
    void assistantToken_fromAssistantToken_denied() {
        assertThatThrownBy(() -> authService.assistantToken(new LoginUser(1L, "a-jti", LoginUser.SCOPE_ASSISTANT)))
                .isInstanceOf(AssistantDenied.class);
        verify(loginSessionStore, never()).saveScoped(any(), anyLong(), any(), any(), any());
    }

    /** 规则：开着 → 登记一张 30 分钟的助手令牌，挂在换它的那张登录令牌下面（那张退出时一起失效） */
    @Test
    void assistantToken_registeredUnderLoginFor30Minutes() {
        Instant now = Instant.parse("2026-11-22T02:00:00Z");
        when(aiSwitchService.enabled()).thenReturn(true);
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(jwtUtil.createScopedToken(eq(1L), anyString(), eq(LoginUser.SCOPE_ASSISTANT), eq(now), eq(Duration.ofMinutes(30))))
                .thenReturn("assist-token");

        AssistantTokenVO vo = authService.assistantToken(new LoginUser(1L, "login-jti"));

        ArgumentCaptor<String> jti = ArgumentCaptor.forClass(String.class);
        verify(loginSessionStore).saveScoped(jti.capture(), eq(1L), eq(LoginUser.SCOPE_ASSISTANT), eq("login-jti"),
                eq(now.plus(Duration.ofMinutes(30))));
        verify(jwtUtil).createScopedToken(1L, jti.getValue(), LoginUser.SCOPE_ASSISTANT, now, Duration.ofMinutes(30));
        assertThat(jti.getValue()).isNotEqualTo("login-jti");
        assertThat(vo.token()).isEqualTo("assist-token");
        assertThat(vo.expiresAt().toInstant()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    private LoginReq req(String username, String password) {
        LoginReq req = new LoginReq();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }
}
