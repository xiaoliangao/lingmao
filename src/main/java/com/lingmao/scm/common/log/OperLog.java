package com.lingmao.scm.common.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 贴在 Controller 方法上：这个操作要记操作日志。
 * 例：@OperLog(module = "用户管理", action = "新增")
 * 谁来读它：module/system/aspect/OperLogAspect（切面）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperLog {

    /** 模块，如 用户管理 */
    String module();

    /** 动作，如 新增、修改、删除 */
    String action();
}
