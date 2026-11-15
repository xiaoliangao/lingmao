package com.lingmao.scm.module.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 供应商 Excel 的一行（"供应商管理"工作表，数据库设计附录 A.1）。列名照领猫，我们多的账期、税率、微信接在后面。
 * 导入时一格一格 set 进来，Service 再翻成实体；导出时从实体填好再一格一格 get 出去
 */
@Data
public class SupplierExcelRow {
    private String code;
    private String name;
    private String shortName;
    /** 分类：领猫的分类名或我们的分类名，几个用逗号、顿号隔开 */
    private String categories;
    private String goodAtTop;
    private String goodAt;
    private String level;
    /** 结算方式（字典 value） */
    private String settleMethod;
    private String designated;
    private String buyer;
    private String frozen;
    private String bankAccountName;
    private String bankName;
    private String bankAccount;
    /** 税号 = 统一社会信用代码 */
    private String creditCode;
    private String invoiceType;
    private String invoiceAddressPhone;
    private String contact;
    private String phone;
    private String fax;
    private String address;
    private String remark;
    private String att01;
    private String att02;
    private String att03;
    private String att04;
    private String att05;
    private String att06;
    private String att07;
    private String att08;
    private String att09;
    private String att10;
    private String externalCode;
    private Integer creditDays;
    private BigDecimal taxPct;
    private String wechat;
    /** 只导出：状态的中文 */
    private String status;
}
