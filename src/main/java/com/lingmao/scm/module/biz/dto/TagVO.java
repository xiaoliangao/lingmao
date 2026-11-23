package com.lingmao.scm.module.biz.dto;

/** 公开库投影；不输出 tenantKey、canonical、deletedToken 或对象关联。 */
public record TagVO(Long id, String name, boolean archived, int version) { }
