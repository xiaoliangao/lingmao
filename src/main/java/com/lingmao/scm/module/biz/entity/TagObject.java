package com.lingmao.scm.module.biz.entity;

/** 标签关联自己的 CAS 版本，与业务单据状态及 version 分开。 */
public record TagObject(long id, String tenantKey, TagObjectType objectType, long objectId,
                        Long companyId, int version) { }
