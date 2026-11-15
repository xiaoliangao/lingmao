package com.lingmao.scm.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字符串按 UTF-8 编码以后最多多少个字节；null 算通过（要求必填另加 @NotBlank）。
 * 为什么有它：@Size 数的是字符，BCrypt 认的是字节，最多 72 个。32 个字符以内的密码，全是汉字就有 96 个字节，
 * @Size(max = 32) 拦不住，到了加密那一步才抛异常，变成 500（RFC 001 §18）。新设的密码都加这一条：
 * <pre>
 * &#64;MaxBytes(value = 72, message = "密码太长：最多 72 个字节，一个汉字算 3 个")
 * </pre>
 */
@Documented
@Constraint(validatedBy = MaxBytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxBytes {

    /** 最多多少个字节 */
    int value();

    /** 没写 message 时的提示；{value} 换成上面的字节数。项目里没有 ValidationMessages.properties，直接写中文 */
    String message() default "太长了：最多 {value} 个字节，一个汉字算 3 个";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
