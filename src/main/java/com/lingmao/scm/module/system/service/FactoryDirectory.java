package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.FactoryBrief;

import java.util.Collection;
import java.util.Map;

/**
 * 工厂账号（RFC 019 §7）要知道供应商是不是加工厂。供应商在 supplier，system 在依赖图的最底下调不了它，
 * 所以接口放在这里、由 supplier 实现（架构 §3.3 第 2 种，和 DefaultCompanyProvider 一样）
 */
public interface FactoryDirectory {

    /** 这几家供应商；不存在的不在返回里 */
    Map<Long, FactoryBrief> find(Collection<Long> supplierIds);

    /** 一家；不存在返回 null */
    default FactoryBrief find(long supplierId) {
        return find(java.util.List.of(supplierId)).get(supplierId);
    }
}
