package com.lingmao.scm.module.biz.entity;

/** 内部持久化值；HTTP 层须映射白名单 DTO，不直接暴露 tenantKey/deletedToken。 */
public record Tag(long id, String tenantKey, String name, String canonical, int version, long deletedToken) {
    public boolean archived() { return deletedToken != 0; }
}
