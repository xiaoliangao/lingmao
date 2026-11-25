package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTest;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DeptSaveReq;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.service.DeptService;
import com.lingmao.scm.module.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 部门在真库上（RFC 018 §1）：外键拦住删有人的部门；删了人（逻辑删除）部门就能删；按部门查人含下级 */
@IntegrationTest
class DeptIT {

    private static final long OPERATOR = -1L;

    @Autowired
    private DeptService deptService;
    @Autowired
    private UserService userService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 规则：部门里有人，Service 报 1005；绕过 Service 直接删，外键拦；把人删了以后，部门从下往上都能删 */
    @Test
    void deptWithUser_blockedUntilUserDeleted() {
        String tag = tag();
        long parent = deptService.create(req(null, "产品" + tag));
        long child = deptService.create(req(parent, "设计" + tag));
        long user = userService.create(userReq(List.of(child)));

        assertThatThrownBy(() -> deptService.delete(child))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.HAS_DOWNSTREAM))
                .hasMessageContaining("1 个人");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_dept WHERE id = ?", child))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM sys_dept WHERE id = ?", parent))
                .isInstanceOf(DataIntegrityViolationException.class);

        userService.delete(user, OPERATOR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user_dept WHERE user_id = ?", Integer.class, user)).isZero();
        deptService.delete(child);
        deptService.delete(parent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_dept WHERE id IN (?, ?)", Integer.class, parent, child)).isZero();
    }

    /** 规则：按上级部门查，下级部门的人也查出来；列表里带部门全路径 */
    @Test
    void pageByDept_includesDescendants() {
        String tag = tag();
        long parent = deptService.create(req(null, "采购" + tag));
        long child = deptService.create(req(parent, "面料" + tag));
        long user = userService.create(userReq(List.of(child)));

        UserQuery query = new UserQuery();
        query.setDeptId(parent);
        PageResult<UserVO> page = userService.page(query);

        assertThat(page.getList()).extracting(UserVO::id).containsExactly(user);
        assertThat(page.getList().getFirst().depts()).extracting(UserVO.DeptBrief::path)
                .containsExactly("采购" + tag + "/面料" + tag);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static DeptSaveReq req(Long parentId, String name) {
        DeptSaveReq req = new DeptSaveReq();
        req.setParentId(parentId);
        req.setName(name);
        req.setSort(0);
        return req;
    }

    private static UserCreateReq userReq(List<Long> deptIds) {
        UserCreateReq req = new UserCreateReq();
        req.setUsername("it" + System.nanoTime());
        req.setPassword("Abc12345");
        req.setNickname("部门测试");
        req.setDeptIds(deptIds);
        req.setProductLines(List.of("APPAREL"));
        return req;
    }
}
