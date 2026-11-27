package com.lingmao.scm.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Objects;

/** 几个价格域各自独立；能不能改仍由原来的动作权限管。 */
public final class FieldPermissions {
    public static final String COST = "techpack:cost:query";
    public static final String ORDER = "order:price:query";
    public static final String PRODUCTION = "production:price:query";
    public static final String CONTACT = "supplier:supplier:contact";
    public static final String BANK = "supplier:supplier:bank";
    /**
     * 对账单、扣款登记上的金额跟着对账的权限走（RFC 019 §5.1）：对账就是对钱，能看对账单的就看得到金额。
     * 对账单上有物料采购价，预置角色里只给本来就看得到采购价、加工价的角色（V66 收回了跟单的）
     */
    public static final String SETTLE = "settle:statement:list";
    public static final String SETTLE_DEDUCTION = "settle:deduction:list";
    private FieldPermissions() { }

    public static boolean has(String permission) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    public static void require(String permission) {
        if (!has(permission)) throw new FieldPermissionDenied(label(permission));
    }

    /** 没权限、没传的保留原值；新加的可选金额保持空。 */
    public static BigDecimal price(String permission, BigDecimal old, BigDecimal submitted) {
        if (has(permission)) return submitted;
        if (submitted != null && (old == null || old.compareTo(submitted) != 0))
            throw new FieldPermissionDenied(label(permission));
        return old;
    }

    public static <T> T preserve(String permission, T old, T submitted) {
        if (has(permission)) return submitted;
        if (submitted != null && !Objects.equals(old, submitted)) throw new FieldPermissionDenied(label(permission));
        return old;
    }

    private static String label(String permission) {
        return switch (permission) {
            case ORDER -> "销售报价";
            case PRODUCTION -> "生产成交价";
            case CONTACT -> "供应商联系方式";
            case BANK -> "供应商银行信息";
            case SETTLE, SETTLE_DEDUCTION -> "对账金额";
            default -> "成本与参考价";
        };
    }
}
