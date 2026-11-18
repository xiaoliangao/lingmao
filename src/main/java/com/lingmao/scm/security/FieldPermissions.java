package com.lingmao.scm.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Objects;

/** Independent business price domains; edit permission is still enforced by the original action. */
public final class FieldPermissions {
    public static final String COST = "techpack:cost:query";
    public static final String ORDER = "order:price:query";
    public static final String PRODUCTION = "production:price:query";
    public static final String CONTACT = "supplier:supplier:contact";
    public static final String BANK = "supplier:supplier:bank";
    private FieldPermissions() { }

    public static boolean has(String permission) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    public static void require(String permission) {
        if (!has(permission)) throw new FieldPermissionDenied(label(permission));
    }

    /** Hidden/null input preserves an existing amount; a new optional amount stays null. */
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
            default -> "成本与参考价";
        };
    }
}
