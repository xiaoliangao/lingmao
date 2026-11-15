package com.lingmao.scm.module.system.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 改密码这个动作（管理员重置、自己改都用它）。密码列标了 NEVER，updateById 不会写它，只能用条件更新改（RFC 001 §4.3）。
     * 传一个空的 SysUser 而不是 null：有实体，update_time、update_by 才会自动填上；空实体的其他字段都是 null，不会被写
     */
    default int updatePassword(Long userId, String encodedPassword) {
        return update(new SysUser(), new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPassword, encodedPassword)
                .eq(SysUser::getId, userId));
    }

    /**
     * 按 id 取用户名和昵称，逻辑删除了的也取（UserService.nicknames）。手写 SQL 是为了绕开逻辑删除：
     * MyBatis-Plus 自带的 selectByIds 会自动加 deleted = false，删了的人在操作记录里就成了空名字
     */
    @Select("""
            <script>
            SELECT id, username, nickname FROM sys_user WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SysUser> selectNamesByIds(@Param("ids") Collection<Long> ids);
}
