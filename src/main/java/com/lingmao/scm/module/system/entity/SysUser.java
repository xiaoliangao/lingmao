package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import com.lingmao.scm.common.mybatis.StringArrayTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sys_user", autoResultMap = true)
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

    /** 默认主体 bas_company.id：新建单据带出（RFC 003 §3.2）。system 不能调 basic，存在由外键保证；停用的主体照样能留着 */
    private Long defaultCompanyId;

    /** 岗位：自由填。部门 V57 起在 sys_user_dept（RFC 018 §1），sys_user.dept 列不再用 */
    private String post;

    /**
     * 设计师编号 01-99，进款号（RFC 005 §3.3）。锁了以后不能改、不能清空，所以保存不写它，
     * 只由 SysUserMapper.updateDesignerNo 的条件更新改（带 designer_no_locked = false）
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String designerNo;

    /**
     * 能看的品类线（字典 product_line 的 value，RFC 013 §2），至少一条；超级管理员不看它，看全部。
     * 加载权限时变成 line:XXX（PermissionServiceImpl），改了下一个请求就生效
     */
    @TableField(typeHandler = StringArrayTypeHandler.class)
    private List<String> productLines;

    /**
     * 要先改密码（RFC 018 §2）：导入建的、管理员新建的、管理员重置密码的是 true，本人改了密码变 false。
     * 只由这几个动作改（SysUserMapper 的条件更新），保存资料不写它
     */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Boolean pwdMustChange;

    /** 第一次用编号自动编款号时由 style 置 true（RFC 005 §3.3）；用户页不改它 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Boolean designerNoLocked;

    /**
     * 工厂账号对应的加工厂、成衣厂（sup_supplier.id，V69、RFC 019 §7）；null = 内部账号。
     * 不空时 PermissionServiceImpl 只给 scope:FACTORY:&lt;id&gt;，角色、品类线、部门都不看
     */
    private Long partnerId;
}
