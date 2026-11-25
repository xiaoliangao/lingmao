package com.lingmao.scm.module.system;

import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 启动时建 admin 只管全新的库：有过 admin（停用、删了、去掉了角色）都不动 */
@ExtendWith(MockitoExtension.class)
class AdminInitializerTest {

    @Mock SysUserMapper users;
    @Mock SysRoleMapper roles;
    @Mock SysUserRoleMapper userRoles;
    @Mock PasswordEncoder encoder;

    private AdminInitializer initializer(String password) {
        return new AdminInitializer(users, roles, userRoles, encoder, password);
    }

    @Test
    void freshDatabaseCreatesAdminWithSuperAdminRole() {
        when(users.existsEverByUsername("admin")).thenReturn(false);
        SysRole role = new SysRole();
        role.setId(1L);
        when(roles.selectOne(any())).thenReturn(role);
        when(encoder.encode("init-pass")).thenReturn("hashed");
        doAnswer(call -> {
            ((SysUser) call.getArgument(0)).setId(7L);
            return 1;
        }).when(users).insert(any(SysUser.class));

        initializer("init-pass").run(null);

        ArgumentCaptor<SysUser> created = ArgumentCaptor.forClass(SysUser.class);
        verify(users).insert(created.capture());
        assertThat(created.getValue().getUsername()).isEqualTo("admin");
        assertThat(created.getValue().getPassword()).isEqualTo("hashed");
        ArgumentCaptor<SysUserRole> granted = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoles).insert(granted.capture());
        assertThat(granted.getValue().getUserId()).isEqualTo(7L);
        assertThat(granted.getValue().getRoleId()).isEqualTo(1L);
    }

    /** admin 删了（existsEver 把删了的也算上）、停用了、角色被去掉了：重启都不补角色、不再建，初始密码也不用 */
    @Test
    void anyAdminEverExistingIsLeftAlone() {
        when(users.existsEverByUsername("admin")).thenReturn(true);

        initializer("init-pass").run(null);

        verify(users, never()).insert(any(SysUser.class));
        verifyNoInteractions(roles, userRoles, encoder);
    }

    @Test
    void freshDatabaseWithoutPasswordCreatesNothing() {
        when(users.existsEverByUsername("admin")).thenReturn(false);

        initializer("").run(null);

        verify(users, never()).insert(any(SysUser.class));
        verifyNoInteractions(userRoles, encoder);
    }
}
