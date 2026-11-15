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
    /** 录加工价时带上：草稿也列出来（打样阶段就报价）。不带只列启用的（以后选进单据） */
    private Boolean includeDraft;
}
