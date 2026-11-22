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

    /** 编码、全称、简称包含它；有「看供应商联系方式」权限的还按联系人查 */
    private String keyword;

    /** 字典 supplier_category 的 value：分类里有它的 */
    private String category;

    /** 状态，可以多选 */
    private List<SupplierStatus> status;

    /** 等级 A–D */
    private String level;
    /** 做这条品类线的（几条线的人才有这个筛选，RFC 013 §2） */
    private String productLine;

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
}
