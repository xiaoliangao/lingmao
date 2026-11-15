package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserOption;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 用户管理：给管理员用的增删改查。和 AuthService（登录、当前用户）分开 */
public interface UserService {

    PageResult<UserVO> page(UserQuery query);

    UserVO getById(Long id);

    /** 选人下拉框：启用的账号，用户名或昵称包含 keyword（空的话不筛），按用户名排，最多 20 个 */
    List<UserOption> options(String keyword);

    /**
     * 一批用户的昵称，id → 昵称：操作记录、单据上的"谁做的"显示名字用。停用的、删除的也给：人走了，记录上照样要看得出是谁。
     * 查不到的 id 不在 map 里。别的域显示人名调这个，不 JOIN sys_user（架构 §3.2）
     */
    Map<Long, String> nicknames(Collection<Long> ids);

    /** 新增用户，返回新 id。用户名已存在抛 BizException */
    Long create(UserCreateReq req);

    /** 修改昵称、手机号、状态。currentUserId 是当前登录的人：不能停用自己 */
    void update(Long id, UserUpdateReq req, Long currentUserId);

    /** 删除用户。currentUserId 是当前登录的人：不能删自己 */
    void delete(Long id, Long currentUserId);

    /** 管理员把某个用户的密码重置成新密码，同时清掉他的登录失败计数（被锁住的马上能用新密码登录） */
    void resetPassword(Long id, String password);

    /** 用户的角色 id */
    List<Long> getRoleIds(Long id);

    /** 重新分配角色：先清空，再按 roleIds 写入。currentUserId 是当前登录的人：不能去掉自己的超级管理员角色 */
    void assignRoles(Long id, List<Long> roleIds, Long currentUserId);

    /**
     * 按昵称找人（导入时把"采购员"这类显示名对到用户，RFC 004 §9）：只找启用的；同名的不止一个算对不上。
     * 返回 昵称 → id，对不上的不在 map 里
     */
    Map<String, Long> idsByNickname(Collection<String> nicknames);

    /**
     * 第一次用设计师编号自动编款号时锁住它（RFC 005 §3.3），之后不能改、不能清空。由 style 在建款的同一个事务里调。
     * 条件比较编号本身：编号刚被管理员改了（款号用的是旧号）→ 1002"设计师编号刚被改过，请重新编号"，整个建款回滚。
     * 已经锁过的再锁一次没关系
     */
    void lockDesignerNo(long userId, String designerNo);
}
