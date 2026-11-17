package com.lingmao.scm.module.inventory.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 盘点范围是"按物料分类"时选的分类（数据库设计 §13.7），关联表：整批删了重插 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("inv_count_category")
public class InvCountCategory {

    private Long countId;

    private Long categoryId;
}
