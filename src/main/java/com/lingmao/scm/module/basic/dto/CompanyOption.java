package com.lingmao.scm.module.basic.dto;

import com.lingmao.scm.module.basic.entity.Company;

/** 主体公司下拉的一项（GET /api/companies/options，只有启用的） */
public record CompanyOption(Long id, String code, String shortName) {

    public static CompanyOption from(Company company) {
        return new CompanyOption(company.getId(), company.getCode(), company.getShortName());
    }
}
