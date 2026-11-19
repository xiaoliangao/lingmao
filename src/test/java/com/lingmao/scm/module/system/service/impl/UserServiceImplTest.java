package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserOption;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginFailCounter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private LoginFailCounter loginFailCounter;

    @Mock
    private DictDataService dictDataService;

    @InjectMocks
    private UserServiceImpl userService;

    /** 品类线的字典校验原样放行（字典的规则在 DictDataServiceImplTest） */
    @BeforeEach
    void lines() {
        lenient().when(dictDataService.requireValues(eq(LineScope.DICT_TYPE), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1) == null ? List.of() : List.copyOf(inv.<java.util.Collection<String>>getArgument(1)));
    }

    /**
     * assignRoles 查角色是否存在用了 Lambda 条件的 in，要先给 SysRole 建表信息（见 RoleServiceImplTest）；
     * 修改用 Updates.replaceById、选人下拉的 Lambda 条件要拼成 SQL 来检查，SysUser 也建一份
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

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

    /** 规则（bug 008）：不能停用自己，不发 update */
    @Test
    void update_disableSelf_throwsAndNotUpdate() {
        assertThatThrownBy(() -> userService.update(1L, updateReq(0), 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("不能停用自己");
        verify(sysUserMapper, never()).update(any(SysUser.class), any());
    }

    /**
     * 规则（bug 005）：保存是整体覆盖，手机号传 null 就是清空，SET 里有 phone = NULL；
     * 密码列标了 NEVER，修改资料不碰它（不然和同时进行的改密码互相覆盖）
     */
    @Test
    void update_phoneNull_clearsPhoneAndNeverTouchesPassword() {
        SysUser old = user(2L, "zhangsan");
        old.setPassword("encoded-old");
        old.setPhone("13800000000");
        when(sysUserMapper.selectById(2L)).thenReturn(old);
        UserUpdateReq req = updateReq(1);                       // phone 没传

        userService.update(2L, req, 1L);

        ArgumentCaptor<UpdateWrapper<SysUser>> wrapper = ArgumentCaptor.captor();
        verify(sysUserMapper).update(same(old), wrapper.capture());
        assertThat(old.getPhone()).isNull();
        // 别的空列（默认主体、部门、岗位）也一起置空；设计师编号走自己的条件更新，不在这里
        assertThat(wrapper.getValue().getSqlSet()).contains("phone = NULL").doesNotContain("password").doesNotContain("designer_no");
    }

    /**
     * 规则（RFC 001 §18 1d）：选人下拉只列启用的人，用户名或昵称包含关键字，最多 20 个、不查总数。
     * 关键字的 or 要包在括号里，不然 status = 1 只管住了用户名那一半
     */
    @Test
    void options_enabledOnly_matchUsernameOrNickname_top20() {
        SysUser zhang = user(2L, "zhangsan");
        zhang.setNickname("张三");
        when(sysUserMapper.selectPage(any(), any())).thenAnswer(inv -> {
            Page<SysUser> page = inv.getArgument(0);
            page.setRecords(List.of(zhang));
            return page;
        });

        List<UserOption> options = userService.options(" 张 ");

        ArgumentCaptor<Page<SysUser>> page = ArgumentCaptor.captor();
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> wrapper = ArgumentCaptor.captor();
        verify(sysUserMapper).selectPage(page.capture(), wrapper.capture());
        assertThat(page.getValue().getSize()).isEqualTo(20);
        assertThat(page.getValue().searchCount()).isFalse();
        assertThat(wrapper.getValue().getSqlSegment())
                .containsPattern("status = #\\{.+\\} AND \\(username LIKE #\\{.+\\} OR nickname LIKE #\\{.+\\}\\)");
        assertThat(wrapper.getValue().getParamNameValuePairs().values()).contains(1, "%张%");
        assertThat(options).containsExactly(new UserOption(2L, "zhangsan", "张三"));
    }

    /** 规则：没有关键字时只按启用筛，不拼 LIKE */
    @Test
    void options_noKeyword_noLike() {
        when(sysUserMapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(userService.options("  ")).isEmpty();

        ArgumentCaptor<LambdaQueryWrapper<SysUser>> wrapper = ArgumentCaptor.captor();
        verify(sysUserMapper).selectPage(any(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSegment()).contains("status =").doesNotContain("LIKE");
    }

    /** 规则（bug 008）：不能去掉自己的超级管理员角色，原来的角色关联一条都不动 */
    @Test
    void assignRoles_removeOwnAdmin_throwsAndNotTouchLinks() {
        when(sysUserMapper.selectById(1L)).thenReturn(user(1L, "admin"));
        when(sysRoleMapper.selectOne(any())).thenReturn(role(1L, SysRole.ADMIN_CODE));
        when(sysUserRoleMapper.exists(any())).thenReturn(true);   // 他现在是超级管理员

        assertThatThrownBy(() -> userService.assignRoles(1L, List.of(2L), 1L))
                .isInstanceOf(BizException.class)
                .hasMessage("不能去掉自己的超级管理员角色");
        verify(sysUserRoleMapper, never()).delete(any());
        verify(sysUserRoleMapper, never()).insert(anyList());
    }

    /** 规则：只拦自己的。去掉别人的超级管理员角色照常保存 */
    @Test
    void assignRoles_removeOthersAdmin_allowed() {
        when(sysUserMapper.selectById(2L)).thenReturn(user(2L, "zhangsan"));
        when(sysRoleMapper.selectCount(any())).thenReturn(1L);

        userService.assignRoles(2L, List.of(3L), 1L);

        verify(sysRoleMapper, never()).selectOne(any());
        verify(sysUserRoleMapper).insert(anyList());
    }

    /** 规则：重置密码，存进库的也是加密后的；走"改密码"这个动作，不走 updateById（密码列标了 NEVER） */
    @Test
    void resetPassword_savesEncodedPassword() {
        when(sysUserMapper.selectById(2L)).thenReturn(user(2L, "zhangsan"));
        when(passwordEncoder.encode("654321")).thenReturn("encoded-654321");

        userService.resetPassword(2L, "654321");

        verify(sysUserMapper).updatePassword(2L, "encoded-654321");
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    /** 规则：重置密码时清掉他的登录失败计数，被锁住的人马上能用新密码登录 */
    @Test
    void resetPassword_clearsLoginFailures() {
        when(sysUserMapper.selectById(2L)).thenReturn(user(2L, "zhangsan"));

        userService.resetPassword(2L, "new-pass-123");

        verify(loginFailCounter).clear("zhangsan");
    }

    /** 规则：取昵称按 id 对上；一个 id 都没有时不查库（IN () 是错的 SQL）。删了的人也取得到，在 DocLogServiceImplIT 用真库测 */
    @Test
    void nicknames_mapsIdToNickname_emptyIdsNoQuery() {
        SysUser zhang = user(5L, "zhangsan");
        zhang.setNickname("张三");
        when(sysUserMapper.selectNamesByIds(List.of(5L))).thenReturn(List.of(zhang));

        assertThat(userService.nicknames(List.of(5L))).containsExactly(Map.entry(5L, "张三"));
        assertThat(userService.nicknames(List.of())).isEmpty();
        verify(sysUserMapper).selectNamesByIds(any());
    }

    /** 规则（RFC 005 §3.3）：设计师编号查重连已删除的账号一起查，编号不回收 */
    @Test
    void create_designerNoOfDeletedUser_rejected() {
        SysUser gone = user(9L, "lisi");
        gone.setNickname("李四");
        gone.setDeleted(true);
        when(sysUserMapper.selectByDesignerNo("07")).thenReturn(gone);
        UserCreateReq req = createReq("wangwu", "Abc12345");
        req.setDesignerNo(" 07 ");

        assertThatThrownBy(() -> userService.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已经给了李四（已删除的账号");
        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    /** 规则：编过款号的编号（designer_no_locked）不能改：条件更新 0 行就报错，整个修改回滚 */
    @Test
    void update_lockedDesignerNoChanged_rejected() {
        SysUser old = user(2L, "zhangsan");
        old.setDesignerNo("07");
        when(sysUserMapper.selectById(2L)).thenReturn(old);
        when(sysUserMapper.updateDesignerNo(2L, null)).thenReturn(0);
        UserUpdateReq req = updateReq(1);                       // 编号没传 = 清空

        assertThatThrownBy(() -> userService.update(2L, req, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("设计师编号 07 已经用来编过款号，不能清空");
    }

    /** 规则：编号没变就不碰它（不查重、不发条件更新），锁了的人照样能改别的资料 */
    @Test
    void update_designerNoUnchanged_notTouched() {
        SysUser old = user(2L, "zhangsan");
        old.setDesignerNo("07");
        when(sysUserMapper.selectById(2L)).thenReturn(old);
        UserUpdateReq req = updateReq(1);
        req.setDesignerNo("07");
        req.setDept("设计部");

        userService.update(2L, req, 1L);

        verify(sysUserMapper, never()).selectByDesignerNo(any());
        verify(sysUserMapper, never()).updateDesignerNo(any(), any());
        assertThat(old.getDept()).isEqualTo("设计部");
    }

    /**
     * 规则（RFC 005 §3.3）：款式第一次用编号自动编号时锁住它，条件比较编号本身。
     * 管理员刚把 07 改成 08，款号用的还是 07：0 行 → 1002，建款整个回滚
     */
    @Test
    void lockDesignerNo_numberChangedMeanwhile_stateChanged() {
        when(sysUserMapper.lockDesignerNo(2L, "07")).thenReturn(0);

        assertThatThrownBy(() -> userService.lockDesignerNo(2L, "07"))
                .isInstanceOf(BizException.class)
                .hasMessage("设计师编号刚被改过，请重新编号")
                .extracting(e -> ((BizException) e).getCode()).isEqualTo(ErrorCodes.STATE_CHANGED);
    }

    /** 编号对得上：锁上，不报错 */
    @Test
    void lockDesignerNo_sameNumber_locked() {
        when(sysUserMapper.lockDesignerNo(2L, "07")).thenReturn(1);

        userService.lockDesignerNo(2L, "07");

        verify(sysUserMapper).lockDesignerNo(2L, "07");
    }

    private UserCreateReq createReq(String username, String password) {
        UserCreateReq req = new UserCreateReq();
        req.setUsername(username);
        req.setPassword(password);
        req.setNickname("张三");
        return req;
    }

    private UserUpdateReq updateReq(int status) {
        UserUpdateReq req = new UserUpdateReq();
        req.setNickname("管理员");
        req.setStatus(status);
        return req;
    }

    private SysUser user(Long id, String username) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    private SysRole role(Long id, String code) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setCode(code);
        return role;
    }
}
