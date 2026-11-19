package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
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

    /** 用户的启用角色 id：多级审批"规则加的级"按角色认人（架构 §4.8），停用、删除了的角色不算 */
    @Select("""
            SELECT r.id FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false
            """)
    List<Long> selectIdsByUserId(@Param("userId") Long userId);

    /** 按 id 取角色名，逻辑删除了的也取（RoleService.names）：手写 SQL 绕开逻辑删除，同 SysUserMapper.selectNamesByIds */
    @Select("""
            <script>
            SELECT id, name FROM sys_role WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SysRole> selectNamesByIds(@Param("ids") Collection<Long> ids);

    /**
     * 用户的数据范围是不是"全部"：多个角色取最宽的，只要有一个启用的角色是 ALL 就是（RFC 001 §8）；是不是超级管理员（品类线看全部，
     * RFC 013 §2）。超级管理员按编码认，数据范围也算全部：他本来就拥有一切，和菜单权限里认 admin 的写法一样（SysMenuMapper）。
     * 过滤器每个请求都调，两样一次查出来
     */
    @Select("""
            SELECT COALESCE(bool_or(r.data_scope = 'ALL' OR r.code = 'admin'), false) AS all_data,
                   COALESCE(bool_or(r.code = 'admin'), false) AS admin
            FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId} AND r.status = 1 AND r.deleted = false
            """)
    ScopeFlags selectScopeFlags(@Param("userId") Long userId);

    record ScopeFlags(boolean allData, boolean admin) {
    }
}
