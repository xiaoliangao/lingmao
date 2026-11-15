package com.lingmao.scm.module.style.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 选款式的弹窗（DataPick kind="style"）：关键字查款号、名称、花名、品牌；品类含下级 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StylePickQuery extends PageQuery {
    private String keyword;
    private Long categoryId;
}
