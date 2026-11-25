package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 人在哪个部门（RFC 018 §1.2）。只有两个外键列，和 SysUserRole 一样不继承基类 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user_dept")
public class SysUserDept {
    private Long userId;
    private Long deptId;
}
