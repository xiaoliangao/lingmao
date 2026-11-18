package com.lingmao.scm.common.lifecycle;

/** Last-resort process termination when resource ownership cannot safely be handed off. */
@FunctionalInterface
public interface HaltStrategy {
    /** Production implementations never return. Tests may throw a sentinel instead of stopping their JVM. */
    void halt(int status);
}
