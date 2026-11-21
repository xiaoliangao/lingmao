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
    /** true：选可以退料的（已审核、发过料的）；false：选还能发料的（已审核、还没发完的） */
    private boolean returnable;
}
