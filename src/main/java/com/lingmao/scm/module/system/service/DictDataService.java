package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;

import java.util.List;

public interface DictDataService {

    PageResult<DictData> page(DictDataQuery query);

    DictData getById(Long id);

    /**
     * 某个类型下的全部选项（停用的也在，带 status），按 sort 排好。走 Redis 缓存。
     * 下拉框只列启用的，显示文字用全部；后端校验新数据的值时也只认启用的（DictOption.enabled）
     */
    List<DictOption> listByType(String typeCode);

    Long create(DictDataSaveReq req);

    void update(Long id, DictDataSaveReq req);

    void delete(Long id);
}