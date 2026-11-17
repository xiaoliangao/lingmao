package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.CompanyBrief;

/**
 * 用户的默认主体（RFC 003 §3.1、PRD §5.7）：新建需求订单、采购单、盘点单时主体默认填它。
 * 主体公司在 basic，system 在依赖图的最底下调不了 basic，所以接口放在这里、由 basic 实现（架构 §3.3 第 2 种："通用的一方定义接口，业务域实现"），
 * 规则只写在 basic 一处。/api/auth/me 用它；以后后端自己建单（算料生成采购单）也用它
 */
public interface DefaultCompanyProvider {

    /**
     * preferredId（用户上的 default_company_id）启用就用它；否则启用的主体只有一个就用那个；都不是返回 null，
     * 单据上必须自己选
     */
    CompanyBrief resolve(Long preferredId);
}
