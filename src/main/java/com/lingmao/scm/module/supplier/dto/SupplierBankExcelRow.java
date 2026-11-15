package com.lingmao.scm.module.supplier.dto;

import lombok.Data;

/**
 * 领猫"银行收付款信息"工作表的一行（数据库设计附录 A.1）。一期一家只存一个账户：
 * 主表的"账户"有值用主表；没有的，取这里这家启用的、默认排序最前的一条，其余的进导入提示
 */
@Data
public class SupplierBankExcelRow {
    private String code;
    private String name;
    private String bankName;
    private String bankAccount;
    private String accountName;
    private String status;
    private Integer sort;
    private String remark;
}
