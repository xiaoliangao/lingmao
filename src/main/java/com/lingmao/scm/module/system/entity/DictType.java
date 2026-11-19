package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体 = 表的镜像，一个字段对应一列，不放任何业务逻辑。
 * 列名 create_time 对应字段 createTime，靠 map-underscore-to-camel-case 自动转换。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_dict_type")
public class DictType extends BaseEntity {
    private String code;
    private String name;
    private String remark;
    private Integer status;

    /** 这个字典的数值叫什么（"备料比 %""默认天数"）；空 = 这个字典不用数值，字典页不显示数值列 */
    private String numLabel;

    /**
     * 字典项文字列（text_value）叫什么，如物料类型的"物料清单谁填"；空 = 字典页不编辑 text_value。
     * 和 textDict 一样是程序认的，只由迁移置（V42），保存类型不碰它
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String textLabel;

    /** text_value 从哪个字典里选（存那个字典的 value），如 bom_owner；空 = 随便填 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String textDict;
}
