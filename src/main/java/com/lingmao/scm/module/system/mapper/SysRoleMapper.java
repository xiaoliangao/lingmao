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

    /**
     * 用户的数据范围是不是"全部"：多个角色取最宽的，只要有一个启用的角色是 ALL 就是（RFC 001 §8）。
     * 超级管理员按编码也算：他本来就拥有一切，和菜单权限里认 admin 的写法一样（SysMenuMapper）
     */
    @Select("""
            SELECT EXISTS (SELECT 1 FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
                           WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false
                             AND (r.data_scope = 'ALL' OR r.code = 'admin'))
            """)
    boolean hasAllDataScope(@Param("userId") Long userId);
}
