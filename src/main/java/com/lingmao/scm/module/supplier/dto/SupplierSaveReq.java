package com.lingmao.scm.module.supplier.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 新建、修改供应商（RFC 004 §4）。保存是整体覆盖：没传、传 null 就是清空。
 * 编码、状态不收（编码新建时取号，状态只由动作改）。
 * <p>
 * ★ 关键字段（全称、信用代码、结算方式、账期、发票类型、税率、开户行、账号、收款户名）：待准入时直接改，准入过的挂起等审批。
 * 账号：没有 supplier:supplier:bank 权限的人传了也不收；和现在遮过的样子一样（6222****1234）的当作没改（RFC 004 §5）
 */
@Data
public class SupplierSaveReq {

    @NotBlank(message = "全称不能为空")
    @Size(max = 100, message = "全称最多 100 个字")
    private String name;

    @NotBlank(message = "简称不能为空")
    @Size(max = 50, message = "简称最多 50 个字")
    private String shortName;

    /** 去空格、转大写后按 GB 32100 校验；空 = 还没有 */
    @Size(max = 40, message = "统一社会信用代码是 18 位")
    private String creditCode;

    @Pattern(regexp = "^[ABCD]?$", message = "等级只能是 A、B、C、D")
    private String level;

    @NotEmpty(message = "至少选一个分类")
    private List<@NotBlank(message = "分类不能为空") String> categories;

    /** 擅长品类的 id */
    private List<Long> goodAtIds;

    @Size(max = 50, message = "联系人最多 50 个字")
    private String contact;

    @Size(max = 32, message = "电话最多 32 个字")
    private String phone;

    @Size(max = 64, message = "微信最多 64 个字")
    private String wechat;

    @Size(max = 255, message = "地址最多 255 个字")
    private String address;

    /** 字典 settle_method */
    @Size(max = 64, message = "结算方式不对")
    private String settleMethod;

    @Min(value = 0, message = "账期不能是负数")
    @Max(value = 3650, message = "账期最多 3650 天")
    private Integer creditDays;

    /** 字典 invoice_type */
    @Size(max = 64, message = "发票类型不对")
    private String invoiceType;

    /** 税率 %，13 表示 13% */
    @DecimalMin(value = "0", message = "税率不能是负数")
    @DecimalMax(value = "100", message = "税率不能超过 100%")
    @Digits(integer = 3, fraction = 2, message = "税率最多 2 位小数")
    private BigDecimal taxPct;

    @Size(max = 100, message = "开户行最多 100 个字")
    private String bankName;

    /** 去掉空格存 */
    @Size(max = 40, message = "账号最多 40 位")
    private String bankAccount;

    @Size(max = 100, message = "收款户名最多 100 个字")
    private String bankAccountName;

    /** 负责采购员 */
    private Long buyerId;

    @Size(max = 500, message = "备注最多 500 个字")
    private String remark;
}
