package com.lingmao.scm.module.purchase.dto;

import com.lingmao.scm.common.page.PageQuery;
import jakarta.validation.constraints.Max;

/** 补货清单是数据库分页；每页最多 100 个 SKU，金额与数量排序在 LIMIT 前完成。 */
public class ReorderQuery extends PageQuery {
    public ReorderQuery() { setSize(50); }

    @Override
    @Max(value = 100, message = "补货清单每页最多 100 行")
    public long getSize() { return super.getSize(); }
}
