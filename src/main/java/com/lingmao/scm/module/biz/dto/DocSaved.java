package com.lingmao.scm.module.biz.dto;

/**
 * 新建单据草稿的返回（前端 types/biz.ts 的 DocSaved）："提交审核"就接着拿 version 调 submit。保存草稿（PUT）只返回新的 version
 */
public record DocSaved(long id, String no, int version) {
}
