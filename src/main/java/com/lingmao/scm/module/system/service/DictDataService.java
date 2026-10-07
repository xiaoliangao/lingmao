package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.entity.DictData;

import java.util.List;

public interface DictDataService {

    PageResult<DictData> page(DictDataQuery query);

    DictData getById(Long id);

    /** 某个类型下启用的选项，按 sort 排好，给下拉框用 */
    List<DictData> listByType(String typeCode);

    Long create(DictDataSaveReq req);

    void update(Long id, DictDataSaveReq req);

    void delete(Long id);
}