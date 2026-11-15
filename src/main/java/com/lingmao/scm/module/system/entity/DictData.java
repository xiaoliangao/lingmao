package com.lingmao.scm.module.system.entity;


import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data //Lombok 在编译时帮你生成 getter、setter、toString
@EqualsAndHashCode(callSuper = true) //@Data 生成的 equals 默认只比较本类的字段，不比较父类里的 id。这样两条 id 不同、内容一样的数据会被判成"相等"，放进 Set 就会被去重。加上这个注解，比较时会把父类字段也算进去
@TableName("sys_dict_data") //@TableName("sys_dict_data")：类名 DictData 和表名 sys_dict_data 对不上，要告诉 MyBatis-Plus 这个类对应哪张表
public
class DictData extends BaseEntity {
    /**
     * 实体只描述"表长什么样"。"参数合不合法"归 DTO 管，"业务上允不允许"归 Service 管
     * id、创建时间、修改时间、deleted 这 6 个字段每张表都有，写在父类里只写一次。
     * 继承之后，插入时自动填时间、删除时变成逻辑删除，这些功能自动就有了
     */
    private String typeCode; //字段写 typeCode，列名是 type_code：application.yaml 里开了 map-underscore-to-camel-case（下划线转驼峰），框架会自动对应。
    private String label;
    private String value;
    private Integer sort; //Integer 可以是 null，所以不用int
    private Integer status;
    private String remark;

    /** 数值，含义看类型的 num_label（物料类型的备料比 %、生产节点的默认天数）；类型没有 num_label 的不用 */
    private BigDecimal numValue;

    /** 内置项：代码按 value 认它，不能删、不能停用（RFC 003 §4.2）。只由迁移置，页面上新建的都是 false，保存不碰它 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Boolean builtin;

    /** 长一点的文字（尺寸部位的默认测量方法），第 3 批 设置 › 工艺标准 里填；字典页的保存不碰它 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String textValue;
}
