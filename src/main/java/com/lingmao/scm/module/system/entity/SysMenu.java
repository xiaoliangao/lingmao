package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    /** 目录 */
    public static final int TYPE_DIR = 1;
    /** 菜单（一个页面） */
    public static final int TYPE_MENU = 2;
    /** 按钮（页面上的一个操作） */
    public static final int TYPE_BUTTON = 3;

    private Long parentId;
    private String name;
    private Integer type;
    private String path;
    private String component;
    private String perms;
    private String icon;
    private Integer sort;
    private Boolean visible;
    private Integer status;
}
