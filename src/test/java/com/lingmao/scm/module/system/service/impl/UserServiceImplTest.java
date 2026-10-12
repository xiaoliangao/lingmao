package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class UserServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserServiceImpl userService;

    /** 规则：用户名已存在，不能新增，也不能走到 insert */
    @Test
    void create_usernameExists_throwsAndNotInsert() {
        when(sysUserMapper.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> userService.create(createReq("zhangsan", "123456")))
                .isInstanceOf(BizException.class)
                .hasMessage("用户名已存在: zhangsan");
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    /** 规则：存进库的是加密后的密码，不是明文 */
    @Test
    void create_ok_savesEncodedPassword() {
        when(sysUserMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("123456")).thenReturn("encoded-123456");

        userService.create(createReq("zhangsan", "123456"));

        ArgumentCaptor<SysUser> saved = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("zhangsan");
        assertThat(saved.getValue().getPassword()).isEqualTo("encoded-123456");
    }

    /** 规则：不能删除自己，而且不去查库、不发 delete */
    @Test
    void delete_self_throwsAndNotDelete() {
        assertThatThrownBy(() -> userService.delete(1L, 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("不能删除自己");
        verify(sysUserMapper, never()).deleteById(any(Long.class));
    }

    /** 规则：重置密码，存进库的也是加密后的 */
    @Test
    void resetPassword_savesEncodedPassword() {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setPassword("old-hash");
        when(sysUserMapper.selectById(2L)).thenReturn(user);
        when(passwordEncoder.encode("654321")).thenReturn("encoded-654321");

        userService.resetPassword(2L, "654321");

        assertThat(user.getPassword()).isEqualTo("encoded-654321");
        verify(sysUserMapper).updateById(user);
    }

    private UserCreateReq createReq(String username, String password) {
        UserCreateReq req = new UserCreateReq();
        req.setUsername(username);
        req.setPassword(password);
        req.setNickname("张三");
        return req;
    }
}
