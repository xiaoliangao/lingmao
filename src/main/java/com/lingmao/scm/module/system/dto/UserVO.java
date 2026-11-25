package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.system.entity.SysUser;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 用户管理列表、详情返回给前端的样子：没有 password 字段
 *
 * @param depts         在哪几个部门（RFC 018 §1），按部门树的顺序
 * @param roles         分了哪些角色
 * @param pwdMustChange 还没改过管理员给的密码：登录后要先改（RFC 018 §2）
 */
public record UserVO(Long id,
                     String username,
                     String nickname,
                     String phone,
                     Integer status,
                     @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
                     OffsetDateTime createTime,
                     Long defaultCompanyId,
                     List<DeptBrief> depts,
                     String post,
                     String designerNo,
                     /** 编号用来编过款号了：不能改、不能清空 */
                     Boolean designerNoLocked,
                     /** 能看的品类线（RFC 013 §2），按字典顺序 */
                     List<String> productLines,
                     List<RoleBrief> roles,
                     Boolean pwdMustChange) {

    /** 部门：name 是本级的名字，path 是全路径"产品部/设计组" */
    public record DeptBrief(Long id, String name, String path) {
    }

    public record RoleBrief(Long id, String name) {
    }

    /** 实体 → VO：一个字段一个字段挑，挑剩下的（密码、删除标记）就不会出去。部门、角色另外填 */
    public static UserVO from(SysUser user, List<DeptBrief> depts, List<RoleBrief> roles) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(),
                user.getPhone(), user.getStatus(), user.getCreateTime(),
                user.getDefaultCompanyId(), depts, user.getPost(), user.getDesignerNo(),
                Boolean.TRUE.equals(user.getDesignerNoLocked()), user.getProductLines(), roles,
                Boolean.TRUE.equals(user.getPwdMustChange()));
    }
}
