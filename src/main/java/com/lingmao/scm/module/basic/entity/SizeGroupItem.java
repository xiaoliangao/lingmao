package com.lingmao.scm.module.basic.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 尺码组的一个尺码（bas_size_group_item，数据库设计 §5.7）：跟着尺码组建、删，没有审计列 */
@Data
@TableName("bas_size_group_item")
public class SizeGroupItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sizeGroupId;

    /** 顺序，从 1 起 */
    private Integer seq;

    /** 1-16 位，大写，如 M、165/88A */
    private String sizeCode;
}
