package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.RoleSaveReq;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.service.RoleService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 删人、删角色在真库上（11-22 的 bug）：账号是逻辑删除，以前删人不去掉角色关联，他的角色就一直"已分配给用户"删不掉。
 * 现在删人一起去掉关联；以前留下的关联（直接往库里插一条模拟）删角色时不算、一起清掉
 */
@IntegrationTest
class RoleDeleteIT {

    /** 操作人：不是被删的那个人就行 */
    private static final long OPERATOR = -1L;

    @Autowired
    private UserService userService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 规则：人还在，角色删不掉；人删了，角色能删，关联都没了 */
    @Test
    void deleteUser_thenRoleCanBeDeleted() {
        long roleId = roleService.create(roleReq());
        long userId = userService.create(userReq());
        userService.assignRoles(userId, List.of(roleId), OPERATOR);

        assertThatThrownBy(() -> roleService.delete(roleId))
                .isInstanceOf(BizException.class)
                .hasMessage("角色已分配给用户，不能删除");

        userService.delete(userId, OPERATOR);
        assertThat(links("user_id", userId)).isZero();
        roleService.delete(roleId);
        assertThat(jdbcTemplate.queryForObject("SELECT deleted FROM sys_role WHERE id = ?", Boolean.class, roleId)).isTrue();
    }

    /** 规则：以前删人留下的关联不挡删角色，删角色时一起清掉 */
    @Test
    void leftoverLinkOfDeletedUser_doesNotBlock() {
        long roleId = roleService.create(roleReq());
        long userId = userService.create(userReq());
        userService.delete(userId, OPERATOR);
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);

        roleService.delete(roleId);

        assertThat(links("role_id", roleId)).isZero();
    }

    private int links(String column, long id) {
        Integer n = jdbcTemplate.queryForObject("SELECT count(*) FROM sys_user_role WHERE " + column + " = ?", Integer.class, id);
        return n == null ? 0 : n;
    }

    private static RoleSaveReq roleReq() {
        RoleSaveReq req = new RoleSaveReq();
        req.setCode("it_role_" + System.nanoTime());
        req.setName("测试角色");
        return req;
    }

    private static UserCreateReq userReq() {
        UserCreateReq req = new UserCreateReq();
        req.setUsername("it" + System.nanoTime());
        req.setPassword("Abc12345");
        req.setNickname("测试");
        req.setProductLines(List.of("APPAREL"));
        return req;
    }
}
