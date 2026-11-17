package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 选生产单：领料列还有没领齐的，returnable = true（退料）列已领 > 0 的 */
@Data
@EqualsAndHashCode(callSuper = true)
public class IssueOptionQuery extends PageQuery {
    /** 单号、款号、加工厂 */
    private String keyword;
    private Long factoryId;
    private boolean returnable;
}
