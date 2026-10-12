package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictTypeQuery;
import com.lingmao.scm.module.system.dto.DictTypeSaveReq;
import com.lingmao.scm.module.system.entity.DictType;

/**
 * Service 接口 = 这个模块对外提供的"能力清单"。
 * Controller 只依赖接口，不依赖实现；以后换实现、加缓存、写测试替身都不用动 Controller。
 */
public interface DictTypeService {

    PageResult<DictType> page(DictTypeQuery query);

    DictType getById(Long id);

    Long create(DictTypeSaveReq req);

    void update(Long id, DictTypeSaveReq req);

    void delete(Long id);
}
