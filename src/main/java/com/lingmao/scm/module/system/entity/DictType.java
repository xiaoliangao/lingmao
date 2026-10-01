package com.lingmao.scm.module.system.entity;

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
}
