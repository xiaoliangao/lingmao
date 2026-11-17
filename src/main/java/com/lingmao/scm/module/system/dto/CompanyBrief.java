package com.lingmao.scm.module.system.dto;

/**
 * 一个主体公司的 id 和简称：/api/auth/me 的默认主体（新建单据时主体默认填它）。
 * 主体公司在 basic（bas_company），system 在最底下读不到它，由 basic 实现 DefaultCompanyProvider 给出来
 */
public record CompanyBrief(Long id, String name) {
}
