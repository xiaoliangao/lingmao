package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.dto.UserInfoVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginFailCounter;
import com.lingmao.scm.security.LoginSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

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
    private PermissionService permissionService;

    @Mock
    private LoginFailCounter loginFailCounter;

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

        String token = authService.login(req("admin", "right"));

        assertThat(token).isEqualTo("token-for-1");
        ArgumentCaptor<String> jti = ArgumentCaptor.forClass(String.class);
        verify(loginSessionStore).save(jti.capture(),eq(1L));
        verify(jwtUtil).createToken(1L, jti.getValue());
    }

    @Test
    void me_returnsUserInfoWithoutPassword() {
        when(sysUserMapper.selectById(1L)).thenReturn(admin());
        when(permissionService.loadRoleCodes(1L)).thenReturn(List.of("admin"));
        when(permissionService.loadPermissions(1L)).thenReturn(Set.of("system:user:list"));
        when(permissionService.loadMenuTree(1L)).thenReturn(List.of());

        UserInfoVO info = authService.me(1L);

        assertThat(info).isEqualTo(new UserInfoVO(1L, "admin", null,
                List.of("admin"), Set.of("system:user:list"), List.of()));
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
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    /**
     * 规则：旧密码对，存进库的是新密码加密后的值
     */
    @Test
    void changePassword_ok_savesEncodedNewPassword() {
        SysUser user = admin();
        when(sysUserMapper.selectById(1L)).thenReturn(user);
        when(passwordEncoder.matches("right-old", "hashed")).thenReturn(true);
        when(passwordEncoder.encode("new-pass-123")).thenReturn("encoded-new");

        authService.changePassword(1L, "right-old", "new-pass-123");

        assertThat(user.getPassword()).isEqualTo("encoded-new");
        verify(sysUserMapper).updateById(user);
    }

    private SysUser admin() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPassword("hashed");
        user.setStatus(1);
        return user;
    }

    private LoginReq req(String username, String password) {
        LoginReq req = new LoginReq();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }
}