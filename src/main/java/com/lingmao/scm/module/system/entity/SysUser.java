package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    private String username;

    /**
     * 密码只由"重置密码""自己改密码"这两个动作改（RFC 001 §4.3），保存用户资料时永远不写它：
     * 修改资料是先查出整条再写回去，要是带着密码一起写，管理员改昵称的同时这个人刚改了密码，就被改回旧的了
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String password;

    private String nickname;
    private String phone;
    private Integer status;
}
