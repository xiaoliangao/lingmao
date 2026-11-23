package com.lingmao.scm.module.biz.entity;

/** 保留身份的关联投影；归档标签仍通过原 link 只读或重排，不能新挂到另一对象。 */
public record TagLink(long id, long objectRefId, int seq, Tag tag) { }
