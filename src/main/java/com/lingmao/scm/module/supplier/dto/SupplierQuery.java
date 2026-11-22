package com.lingmao.scm.module.supplier.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 供应商列表的条件（RFC 004 §8），导出也用它。都不传就是全部。
 * 分段控件是分类；筛选弹层里是状态、等级、采购员和三个开关；关键字查编码、全称、简称、联系人
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplierQuery extends PageQuery {

    /** 编码、全称、简称、联系人包含它 */
    private String keyword;

    /** 字典 supplier_category 的 value：分类里有它的 */
    private String category;

    /** 状态，可以多选 */
    private List<SupplierStatus> status;

    /** 等级 A–D */
    private String level;

    /** 负责采购员 */
    private Long buyerId;

    /** 待我审批：准入审批中、或者有待批改动，而且申请人不是我 */
    private Boolean toApprove;

    /** 缺统一社会信用代码（Q29：导入的多数没有） */
    private Boolean missingCredit;

    /** 有证照 30 天内到期（已经过期的也算） */
    private Boolean certExpiring;

    /** 只要这几家：导出选中的用 */
    private List<Long> ids;
    /** 首页审核待办：在 SQL 分页前按提交时间、ID 排序。 */
    private boolean oldestFirst;
}
