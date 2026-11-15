package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictDataVO;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;

import java.util.List;

public interface DictDataService {

    /** 字典页：带 trade（代工保留值）标记 */
    PageResult<DictDataVO> page(DictDataQuery query);

    DictData getById(Long id);

    /**
     * 某个类型下的全部选项（停用的也在，带 status），按 sort 排好。走 Redis 缓存。
     * 下拉框只列启用的，显示文字用全部；后端校验新数据的值时也只认启用的（DictOption.enabled）。
     * TRADE 关时滤掉代工的保留值（客户来样、客户订单，RFC 003 §3.3）：缓存里是全的，取出来以后再滤
     */
    List<DictOption> listByType(String typeCode);

    Long create(DictDataSaveReq req);

    /** value、类型建好不改；内置项不能停用（RFC 003 §4.2） */
    void update(Long id, DictDataSaveReq req);

    /** 内置项不能删 */
    void delete(Long id);
}