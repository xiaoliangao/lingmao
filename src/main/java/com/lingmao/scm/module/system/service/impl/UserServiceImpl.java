package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserOption;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.entity.SysRole;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.entity.SysUserDept;
import com.lingmao.scm.module.system.entity.SysUserRole;
import com.lingmao.scm.module.system.mapper.SysRoleMapper;
import com.lingmao.scm.module.system.mapper.SysUserDeptMapper;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.mapper.SysUserRoleMapper;
import com.lingmao.scm.module.system.service.DeptService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginFailCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
    private final DictDataService dictDataService;
    private final SysUserDeptMapper userDeptMapper;
    private final DeptService deptService;

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        String kw = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().strip() : null;
        List<Long> deptUserIds = null;
        if (query.getDeptId() != null) {
            List<Long> deptIds = deptService.withDescendants(query.getDeptId());
            deptUserIds = deptIds.isEmpty() ? List.of() : userDeptMapper.selectList(new LambdaQueryWrapper<SysUserDept>()
                    .in(SysUserDept::getDeptId, deptIds)).stream().map(SysUserDept::getUserId).distinct().toList();
            if (deptUserIds.isEmpty()) {
                return new PageResult<>(List.of(), 0);
            }
        }
        List<Long> roleUserIds = null;
        if (query.getRoleId() != null) {
            roleUserIds = sysUserRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getRoleId, query.getRoleId())).stream().map(SysUserRole::getUserId).toList();
            if (roleUserIds.isEmpty()) {
                return new PageResult<>(List.of(), 0);
            }
        }
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .and(kw != null, w -> w.like(SysUser::getUsername, kw).or().like(SysUser::getNickname, kw)
                        .or().like(SysUser::getPhone, kw))
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .in(deptUserIds != null, SysUser::getId, deptUserIds)
                .in(roleUserIds != null, SysUser::getId, roleUserIds)
                .orderByDesc(SysUser::getId);
        var page = sysUserMapper.selectPage(query.toPage(), wrapper);
        return new PageResult<>(toVOs(page.getRecords()), page.getTotal());
    }

    @Override
    public UserVO getById(Long id) {
        return toVOs(List.of(getEntity(id))).getFirst();
    }

    /** 实体 → VO，部门、角色一次查出来填上 */
    private List<UserVO> toVOs(List<SysUser> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        List<Long> ids = users.stream().map(SysUser::getId).toList();
        Map<Long, List<UserVO.DeptBrief>> depts = deptsOf(ids);
        Map<Long, List<UserVO.RoleBrief>> roles = new HashMap<>();
        sysUserRoleMapper.selectRoleNames(ids).forEach(r -> roles.computeIfAbsent(r.userId(), k -> new ArrayList<>())
                .add(new UserVO.RoleBrief(r.roleId(), r.roleName())));
        return users.stream().map(u -> UserVO.from(u, depts.getOrDefault(u.getId(), List.of()),
                roles.getOrDefault(u.getId(), List.of()))).toList();
    }

    /** 这几个人各在哪些部门，按全路径排 */
    private Map<Long, List<UserVO.DeptBrief>> deptsOf(Collection<Long> userIds) {
        List<SysUserDept> links = userDeptMapper.selectByUserIds(userIds);
        if (links.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> paths = deptService.paths();
        Map<Long, List<UserVO.DeptBrief>> out = new HashMap<>();
        for (SysUserDept link : links) {
            String path = paths.get(link.getDeptId());
            if (path != null) {
                out.computeIfAbsent(link.getUserId(), k -> new ArrayList<>()).add(new UserVO.DeptBrief(link.getDeptId(),
                        path.substring(path.lastIndexOf(DeptServiceImpl.SEPARATOR) + 1), path));
            }
        }
        out.values().forEach(list -> list.sort(Comparator.comparing(UserVO.DeptBrief::path)));
        return out;
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
        List<SysUser> users = sysUserMapper.selectPage(new Page<>(1, OPTIONS_LIMIT, false), wrapper).getRecords();
        Map<Long, List<UserVO.DeptBrief>> depts = users.isEmpty() ? Map.of() : deptsOf(users.stream().map(SysUser::getId).toList());
        return users.stream().map(u -> {
            List<UserVO.DeptBrief> mine = depts.get(u.getId());
            String names = mine == null ? null : mine.stream().map(UserVO.DeptBrief::name).collect(Collectors.joining("、"));
            return new UserOption(u.getId(), u.getUsername(), u.getNickname(), names);
        }).toList();
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
    public boolean isEnabled(long id) {
        return sysUserMapper.exists(new LambdaQueryWrapper<SysUser>().eq(SysUser::getId, id).eq(SysUser::getStatus, 1));
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
        user.setDefaultCompanyId(req.getDefaultCompanyId());
        user.setPost(trimToNull(req.getPost()));
        user.setProductLines(dictDataService.requireValues(LineScope.DICT_TYPE, req.getProductLines(), null, "品类线"));
        String designerNo = trimToNull(req.getDesignerNo());
        ensureDesignerNoFree(designerNo, null);
        user.setDesignerNo(designerNo);
        // 管理员定的初始密码他知道：本人第一次登录要先改（RFC 018 §2）
        user.setPwdMustChange(true);
        List<Long> deptIds = distinct(req.getDeptIds());
        deptService.requireExisting(deptIds);
        sysUserMapper.insert(user);   // 同时给两个人同一个编号：uk_sys_user_designer_no 拦住后一个（1000）
        insertDepts(user.getId(), deptIds);
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
        user.setDefaultCompanyId(req.getDefaultCompanyId());
        user.setPost(trimToNull(req.getPost()));
        user.setProductLines(dictDataService.requireValues(LineScope.DICT_TYPE, req.getProductLines(), user.getProductLines(), "品类线"));
        List<Long> deptIds = distinct(req.getDeptIds());
        deptService.requireExisting(deptIds);
        // 整体覆盖：手机号传 null 就是清空（bug 005）。密码、设计师编号、要不要改密码标了 NEVER，这里不会碰它们
        sysUserMapper.update(user, Updates.replaceById(user));
        changeDesignerNo(user, trimToNull(req.getDesignerNo()));
        // 部门也整体覆盖：不传（null）和传空表一样，是不在任何部门
        userDeptMapper.delete(new LambdaQueryWrapper<SysUserDept>().eq(SysUserDept::getUserId, id));
        insertDepts(id, deptIds);
    }

    private void insertDepts(Long userId, List<Long> deptIds) {
        if (!deptIds.isEmpty()) {
            userDeptMapper.insert(deptIds.stream().map(d -> new SysUserDept(userId, d)).toList());
        }
    }

    private static List<Long> distinct(List<Long> ids) {
        return ids == null ? List.of() : List.copyOf(new LinkedHashSet<>(ids.stream().filter(Objects::nonNull).toList()));
    }

    @Override
    @Transactional
    public void delete(Long id, Long currentUserId) {
        if (Objects.equals(id, currentUserId)) {
            throw new BizException("不能删除自己");
        }
        getEntity(id);
        sysUserMapper.deleteById(id);
        // 账号是逻辑删除，角色关联真删：不然他的角色一直"已分配给用户"、删不掉（和删角色时去掉菜单关联一样）
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>()
                .eq(SysUserRole::getUserId, id));
        // 部门关联同理：不然部门里一直"还有人"，删不掉（RFC 018 §1.2）
        userDeptMapper.delete(new LambdaQueryWrapper<SysUserDept>().eq(SysUserDept::getUserId, id));
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

    /**
     * 改、清空设计师编号（RFC 003 §3.2、RFC 005 §3.3）：没变不动；变了先查有没有别人用着（删了的人也算），
     * 再按 designer_no_locked = false 条件更新，0 行说明编号已经用来编过款号了
     */
    private void changeDesignerNo(SysUser user, String designerNo) {
        if (Objects.equals(designerNo, user.getDesignerNo())) {
            return;
        }
        ensureDesignerNoFree(designerNo, user.getId());
        if (sysUserMapper.updateDesignerNo(user.getId(), designerNo) == 0) {
            throw new BizException("设计师编号 " + user.getDesignerNo() + " 已经用来编过款号，不能"
                    + (designerNo == null ? "清空" : "改") + "：老款号里的编号要一直对应这个人");
        }
    }

    /** 设计师编号全表唯一，离职（删除）的人的编号也不回收。excludeId 是自己 */
    private void ensureDesignerNoFree(String designerNo, Long excludeId) {
        if (designerNo == null) {
            return;
        }
        SysUser owner = sysUserMapper.selectByDesignerNo(designerNo);
        if (owner != null && !owner.getId().equals(excludeId)) {
            throw new BizException("设计师编号 " + designerNo + " 已经给了" + owner.getNickname()
                    + (Boolean.TRUE.equals(owner.getDeleted()) ? "（已删除的账号，编号不回收）" : ""));
        }
    }

    private static String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.strip() : null;
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

    @Override
    @Transactional
    public void lockDesignerNo(long userId, String designerNo) {
        if (designerNo == null || sysUserMapper.lockDesignerNo(userId, designerNo) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "设计师编号刚被改过，请重新编号");
        }
    }
}
