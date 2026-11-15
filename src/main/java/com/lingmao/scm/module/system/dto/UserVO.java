package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.system.entity.SysUser;

import java.time.OffsetDateTime;

/** 用户管理列表、详情返回给前端的样子：没有 password 字段 */
public record UserVO(Long id,
                     String username,
                     String nickname,
                     String phone,
                     Integer status,
                     @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                     OffsetDateTime createTime,
                     Long defaultCompanyId,
                     String dept,
                     String post,
                     String designerNo,
                     /** 编号用来编过款号了：不能改、不能清空 */
                     Boolean designerNoLocked) {

    /** 实体 → VO：一个字段一个字段挑，挑剩下的（密码、删除标记）就不会出去 */
    public static UserVO from(SysUser user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(),
                user.getPhone(), user.getStatus(), user.getCreateTime(),
                user.getDefaultCompanyId(), user.getDept(), user.getPost(), user.getDesignerNo(),
                Boolean.TRUE.equals(user.getDesignerNoLocked()));
    }
}
