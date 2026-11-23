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

    /** 授权图变更的统一第一把锁；必须先于任何角色、菜单或用户行锁。 */
    @Select("SELECT 1 FROM scm_lock_authorization_graph()")
    int lockAuthorizationGraph();

    /** 菜单整体替换和角色状态修改共用该行锁，随后才读关联、校验和写入。 */
    @Select("SELECT id FROM sys_role WHERE id=#{id} AND NOT deleted FOR NO KEY UPDATE")
    Long lockAuthorization(@Param("id") Long id);

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
