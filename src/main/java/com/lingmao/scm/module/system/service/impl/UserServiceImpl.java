package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserOption;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginFailCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** 选人下拉框一次最多给多少个：再多就该让他多打几个字了 */
    static final int OPTIONS_LIMIT = 20;

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final LoginFailCounter loginFailCounter;

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .orderByDesc(SysUser::getId);
        return PageResult.of(sysUserMapper.selectPage(query.toPage(), wrapper).convert(UserVO::from));
    }

    @Override
    public UserVO getById(Long id) {
        return UserVO.from(getEntity(id));
    }

    @Override
    public List<UserOption> options(String keyword) {
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getStatus, 1)
                // 用户名或昵称包含关键字；括号要加：不加的话 status = 1 只管住了用户名那一半
                .and(kw != null, w -> w.like(SysUser::getUsername, kw).or().like(SysUser::getNickname, kw))
                .orderByAsc(SysUser::getUsername);
        // 只取前 20 个，不查总数（第三个参数 false）
        return sysUserMapper.selectPage(new Page<>(1, OPTIONS_LIMIT, false), wrapper)
                .getRecords().stream().map(UserOption::from).toList();
    }

    @Override
    public Map<Long, String> nicknames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return sysUserMapper.selectNamesByIds(ids).stream()
                .collect(Collectors.toMap(SysUser::getId, SysUser::getNickname));
    }

    @Override
    @Transactional
    public Long create(UserCreateReq req) {
        boolean exists = sysUserMapper.exists(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, req.getUsername()));
        if (exists) {
            throw new BizException("用户名已存在: " + req.getUsername());
        }
        SysUser user = new SysUser();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setPhone(req.getPhone());
        sysUserMapper.insert(user);
        return user.getId();
    }

    @Override
    @Transactional
    public void update(Long id, UserUpdateReq req, Long currentUserId) {
        // 停用自己：这次保存一完成自己就进不来了，唯一的管理员这么做就再没人能管（bug 008）
        if (Objects.equals(id, currentUserId) && Objects.equals(req.getStatus(), 0)) {
            throw new BizException("不能停用自己");
        }
        SysUser user = getEntity(id);
        user.setNickname(req.getNickname());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus());
        // 整体覆盖：手机号传 null 就是清空（bug 005）。密码标了 NEVER，这里不会碰它
        sysUserMapper.update(user, Updates.replaceById(user));
    }

    @Override
    @Transactional
    public void delete(Long id, Long currentUserId) {
        if (Objects.equals(id, currentUserId)) {
            throw new BizException("不能删除自己");
        }
        getEntity(id);
        sysUserMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void resetPassword(Long id, String password) {
        SysUser user = getEntity(id);
        sysUserMapper.updatePassword(id, passwordEncoder.encode(password));
        // 他可能就是输错被锁住了才来找管理员：重置以后马上能用新密码登录
        loginFailCounter.clear(user.getUsername());
    }

    @Override
    public List<Long> getRoleIds(Long id) {
        getEntity(id);
        return sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, id))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    @Override
    @Transactional
    public void assignRoles(Long id, List<Long> roleIds, Long currentUserId) {
        getEntity(id);
        List<Long> distinctIds = roleIds.stream().distinct().toList();
        if (Objects.equals(id, currentUserId)) {
            ensureKeepsOwnAdmin(id, distinctIds);
        }
        if (!distinctIds.isEmpty()) {
            Long found = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                    .in(SysRole::getId, distinctIds));
            if (found != distinctIds.size()) {
                throw new BizException("有角色不存在或已删除");
            }
        }
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id));
        if (!distinctIds.isEmpty()) {
            sysUserRoleMapper.insert(distinctIds.stream().map(roleId -> new SysUserRole(id, roleId)).toList());
        }
    }

    /**
     * 不能去掉自己的超级管理员角色：唯一的超级管理员把自己的去掉，就再没人能进管理后台（bug 008）。
     * 去掉别人的可以；自己本来就不是超级管理员的，不受影响
     */
    private void ensureKeepsOwnAdmin(Long userId, List<Long> roleIds) {
        SysRole admin = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getCode, SysRole.ADMIN_CODE));
        if (admin == null || roleIds.contains(admin.getId())) {
            return;
        }
        boolean isAdmin = sysUserRoleMapper.exists(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getRoleId, admin.getId()));
        if (isAdmin) {
            throw new BizException("不能去掉自己的超级管理员角色");
        }
    }

    /** 查实体，不存在抛 404。只在本类里用，所以是 private，返回实体不返回 VO */
    private SysUser getEntity(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + id);
        }
        return user;
    }

    @Override
    public Map<String, Long> idsByNickname(Collection<String> nicknames) {
        if (nicknames == null || nicknames.isEmpty()) {
            return Map.of();
        }
        List<SysUser> users = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .select(SysUser::getId, SysUser::getNickname)
                .eq(SysUser::getStatus, 1)
                .in(SysUser::getNickname, nicknames));
        // 同名的不止一个：不知道是哪个，算对不上
        Map<String, List<Long>> byName = users.stream().collect(Collectors.groupingBy(SysUser::getNickname,
                Collectors.mapping(SysUser::getId, Collectors.toList())));
        return byName.entrySet().stream().filter(e -> e.getValue().size() == 1)
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst()));
    }
}
