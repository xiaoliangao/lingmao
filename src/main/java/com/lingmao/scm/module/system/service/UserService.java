package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;

import java.util.List;

/** 用户管理：给管理员用的增删改查。和 AuthService（登录、当前用户）分开 */
public interface UserService {

    PageResult<UserVO> page(UserQuery query);

    UserVO getById(Long id);

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
}
