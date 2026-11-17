package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 工艺指示一段的文字（tp_craft_section，数据库设计 §9.11）。空段不存行。
 * 插图不在这里：挂 biz_attachment（TECH_PACK、资料包 id、category = 段代码），段的行换 id 也不影响插图
 */
@Data
@TableName("tp_craft_section")
public class CraftText {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long techPackId;

    private CraftSection section;

    private String content;
}
