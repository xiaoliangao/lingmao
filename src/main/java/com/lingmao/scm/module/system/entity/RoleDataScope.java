package com.lingmao.scm.module.system.entity;

/**
 * 角色的数据范围（PRD §3.2，sys_role.data_scope，V8）。库里存枚举名。
 * 一个人有几个角色时取最宽的：只要有一个启用的角色是 ALL（或者他是超级管理员），加载权限时就多给一个
 * {@link #ALL_AUTHORITY}，以后查单据的 DataScope 工具（第 3 批）和前端的"我的 / 全部"切换都只看它，不用另外查库（架构 §8）。
 * <p>
 * 名字不叫 DataScope：那是第 3 批查询时用的工具（架构 §8），这里只是角色上存的设置。
 */
public enum RoleDataScope {

    /** 全部：看所有人的单据 */
    ALL,

    /** 本人：只看自己是制单人，或者单据上的业务员、跟单员、采购员是自己的单据 */
    SELF;

    /** 数据范围是"全部"的人多出的这个权限标识 */
    public static final String ALL_AUTHORITY = "data:all";

    /**
     * 角色勾了"审核不受数据范围限制"（sys_role.audit_any_scope，V49）的人多出的权限标识：单级审核的单据待审时不按本人筛，
     * 还要有那种单的审核权限才管用（DataScope.orAuditing）
     */
    public static final String AUDIT_ANY_AUTHORITY = "data:audit-any";
}
