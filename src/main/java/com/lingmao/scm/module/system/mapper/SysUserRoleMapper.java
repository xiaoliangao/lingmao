package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 这个角色还分给了没删的人没有。删了的人留下的关联不算：11-22 以前删用户不去掉他的角色关联，
     * 那些人的角色一直删不掉（"角色已分配给用户"）
     */
    @Select("SELECT EXISTS (SELECT 1 FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id AND u.deleted = false"
            + " WHERE ur.role_id = #{roleId})")
    boolean assignedToActiveUser(@Param("roleId") Long roleId);
}
