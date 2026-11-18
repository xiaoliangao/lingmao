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

    /** 自己改密码：只替换刚才用旧密码核对过的那个哈希。 */
    default int changePasswordIfCurrent(Long userId, String expectedHash, String encodedPassword) {
        return update(new SysUser(), new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPassword, encodedPassword)
                .eq(SysUser::getId, userId).eq(SysUser::getPassword, expectedHash).eq(SysUser::getStatus, 1));
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

    /**
     * 谁用着这个设计师编号，删了的人也查（RFC 003 §3.2）：编号全表唯一、离职不回收，老款号上的编号永远只对应一个人。
     * 手写 SQL 绕开逻辑删除，同 selectNamesByIds
     */
    @Select("SELECT id, nickname, deleted FROM sys_user WHERE designer_no = #{designerNo}")
    SysUser selectByDesignerNo(@Param("designerNo") String designerNo);

    /**
     * 用户页改、清空设计师编号（RFC 005 §3.3）：条件带 designer_no_locked = false，锁了的（编过款号的）改不动，返回 0 行。
     * 和 style 第一次用编号时的锁（比较编号本身）两边谁后到谁失败
     */
    default int updateDesignerNo(Long userId, String designerNo) {
        return update(new SysUser(), new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getDesignerNo, designerNo)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDesignerNoLocked, false));
    }

    /**
     * 锁住设计师编号（RFC 005 §3.3）：SET designer_no_locked = true WHERE id = ? AND designer_no = ?。
     * 比较编号本身，0 行说明编号刚被改了（或者人被删了）。和 updateDesignerNo 两边谁后到谁失败
     */
    default int lockDesignerNo(Long userId, String designerNo) {
        return update(new SysUser(), new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getDesignerNoLocked, true)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDesignerNo, designerNo));
    }
}
