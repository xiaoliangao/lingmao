package com.lingmao.scm.module.style.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 款式选的颜色（sty_style_color，数据库设计 §8.2）：只追加，顺序能拖；生成过 SKU 的只能停用。
 * 跟着款式整体改，没有审计列
 */
@Data
@TableName("sty_style_color")
public class StyleColor {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long styleId;

    private Long colorId;

    /** 顺序，从 1 起 */
    private Integer seq;

    /** 1 启用 0 停用：停用的颜色不能再下单；不连带停用它的 SKU */
    private Integer status;
}
