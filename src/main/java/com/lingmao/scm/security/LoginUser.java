package com.lingmao.scm.security;

/** 当前登录的是谁：用户 id，以及这次登录的 token 编号（退出时要用） */
public record LoginUser(Long userId, String jti) {
    /**
     * record 是什么：Java 16 起有的一种写法，专门用来定义"只装数据、创建以后不改"的类。这一行等于自动生成了：两个 private final 字段、一个两参数的构造器、两个读取方法 userId() 和 jti()（注意没有 get 前缀），还有 equals、toString。
     *
     * 为什么这里用 record 不用 @Data 类：LoginUser 在过滤器里创建，之后只读、不改，正好是 record 的用途。实体和 DTO 不用 record，因为框架要先 new 一个空的再一个个 set。
     */
}