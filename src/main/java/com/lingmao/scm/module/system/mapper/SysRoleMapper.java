package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 用户的启用角色编码 */
    @Select("""
            SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false
            ORDER BY r.sort, r.id
            """)
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
