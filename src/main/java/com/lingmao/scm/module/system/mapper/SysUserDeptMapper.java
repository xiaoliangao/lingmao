package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysUserDept;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysUserDeptMapper extends BaseMapper<SysUserDept> {

    /** 每个部门直属的、没删的人数（部门树上显示）：删了的人的关联删用户时已经去掉，这里再按 deleted 筛一道 */
    @Select("SELECT ud.dept_id, count(*) AS n FROM sys_user_dept ud JOIN sys_user u ON u.id = ud.user_id AND u.deleted = false"
            + " GROUP BY ud.dept_id")
    List<DeptCount> countByDept();

    /** 这几个人各在哪些部门 */
    @Select("""
            <script>
            SELECT user_id, dept_id FROM sys_user_dept WHERE user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SysUserDept> selectByUserIds(@Param("userIds") Collection<Long> userIds);

    record DeptCount(Long deptId, Long n) {
    }
}
