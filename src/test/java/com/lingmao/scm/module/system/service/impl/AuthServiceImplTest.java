package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.LoginReq;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private AuthServiceImpl authService;

    /** 规则：用户名不存在，报“用户名或密码错误”，不签发 token */
    @Test
    void login_userNotFound_throwsAndNoToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> authService.login(req("nobody", "123456")))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码错误");
        verify(jwtUtil, never()).createToken(any());
    }

    /** 规则：密码错，报同一句话，不签发 token */
    @Test
    void login_wrongPassword_throwsAndNoToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req("admin", "wrong")))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名或密码错误");
        verify(jwtUtil, never()).createToken(any());
    }

    /** 规则：用户名和密码都对，返回给这个用户签发的 token */
    @Test
    void login_ok_returnsToken() {
        when(sysUserMapper.selectOne(any())).thenReturn(admin());
        when(passwordEncoder.matches("right", "hashed")).thenReturn(true);
        when(jwtUtil.createToken(1L)).thenReturn("token-for-1");

        String token = authService.login(req("admin", "right"));

        assertThat(token).isEqualTo("token-for-1");
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