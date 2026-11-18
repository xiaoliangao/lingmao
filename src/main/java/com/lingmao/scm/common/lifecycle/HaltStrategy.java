package com.lingmao.scm.common.lifecycle;

/** 资源没法安全交出时的最后手段：结束进程。 */
@FunctionalInterface
public interface HaltStrategy {
    /** 生产实现不会返回；测试里可以抛一个哨兵异常，不真停 JVM。 */
    void halt(int status);
}
