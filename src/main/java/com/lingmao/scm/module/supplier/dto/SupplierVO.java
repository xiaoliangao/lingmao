package com.lingmao.scm.module.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 供应商详情（RFC 004 §8）：档案 + 分类 + 擅长品类 + 待批改动 + 领猫附加属性。
 * 账号一律遮成前 4 后 4，有 bank 权限的人点"显示"另调 /bank 取全号（RFC 004 §5）
 */
@Data
public class SupplierVO {
    private Long id;
    private String code;
    private String name;
    private String shortName;
    private String creditCode;
    private String level;
    /** 做哪几条品类线，字典 product_line 的 value，按字典顺序 */
    private List<String> productLines;
    private SupplierStatus status;
    private List<String> categories;
    private List<GoodAtVO> goodAt;
    private String contact;
    private String phone;
    private String wechat;
    private String address;
    private String settleMethod;
    private Integer creditDays;
    private String invoiceType;
    private BigDecimal taxPct;
    private String bankName;
    /** 遮过的账号 */
    private String bankAccount;
    private String bankAccountName;
    private Long buyerId;
    private String buyerName;
    private Long admitApplyBy;
    private String admitApplyByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime admitApplyTime;
    private String admitByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime admitTime;
    private String blackReason;
    private String blackByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime blackTime;
    private String remark;
    /** 领猫附加属性，只读显示 */
    private Map<String, String> legacyAttrs;
    /** 等审批的改动；没有是 null */
    private ChangeVO pendingChange;
    /**
     * 当前用户现在能做的：状态动作（submit、withdraw、audit、reject、disable、enable、blacklist、unblacklist）
     * 再加 edit、remove。= 转换表允许的 ∩ 有权限的 ∩ 业务条件（自己不能批自己的、只有申请人能撤回）
     */
    private List<String> actions;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    /** 擅长的一个品类 */
    public record GoodAtVO(Long id, String name) {
    }
}
