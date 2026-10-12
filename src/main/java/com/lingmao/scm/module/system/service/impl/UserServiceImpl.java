package com.lingmao.scm.module.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.UserCreateReq;
import com.lingmao.scm.module.system.dto.UserQuery;
import com.lingmao.scm.module.system.dto.UserUpdateReq;
import com.lingmao.scm.module.system.dto.UserVO;
import com.lingmao.scm.module.system.entity.SysUser;
import com.lingmao.scm.module.system.mapper.SysUserMapper;
import com.lingmao.scm.module.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;

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
    public void update(Long id, UserUpdateReq req) {
        SysUser user = getEntity(id);
        user.setNickname(req.getNickname());
        user.setPhone(req.getPhone());
        user.setStatus(req.getStatus());
        sysUserMapper.updateById(user);
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
        user.setPassword(passwordEncoder.encode(password));
        sysUserMapper.updateById(user);
    }

    /** 查实体，不存在抛 404。只在本类里用，所以是 private，返回实体不返回 VO */
    private SysUser getEntity(Long id) {
        SysUser user = sysUserMapper.selectById(id);
        if (user == null) {
            throw new BizException(404, "用户不存在: " + id);
        }
        return user;
    }
}
