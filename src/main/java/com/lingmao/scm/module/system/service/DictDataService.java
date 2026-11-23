package com.lingmao.scm.module.system.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.system.dto.DictDataQuery;
import com.lingmao.scm.module.system.dto.DictDataSaveReq;
import com.lingmao.scm.module.system.dto.DictDataVO;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.entity.DictData;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface DictDataService {

    /** 可信业务模板的有界启用项读取，超限明确拒绝；maxRows只由服务端给定。 */
    java.util.List<com.lingmao.scm.module.system.entity.DictData> enabledForUse(String typeCode, int maxRows);


    /** 字典页：带 trade（代工保留值）标记 */
    PageResult<DictDataVO> page(DictDataQuery query);

    DictData getById(Long id);

    /**
     * 某个类型下的全部选项（停用的也在，带 status），按 sort 排好。走 Redis 缓存。
     * 下拉框只列启用的，显示文字用全部；后端校验新数据的值时也只认启用的（DictOption.enabled）。
     * TRADE 关时滤掉代工的保留值（客户来样、客户订单，RFC 003 §3.3）：缓存里是全的，取出来以后再滤
     */
    List<DictOption> listByType(String typeCode);

    /** 已缓存字典的编码 → 展示名，不让各聚合接口重复实现。 */
    default Map<String, String> labels(String typeCode) {
        return listByType(typeCode).stream().collect(java.util.stream.Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }

    Long create(DictDataSaveReq req);

    /** value、类型建好不改；内置项不能停用（RFC 003 §4.2） */
    void update(Long id, DictDataSaveReq req);

    /** 内置项不能删 */
    void delete(Long id);

    /**
     * 一个类型下的全部项（停用的也在），带 text_value（长文字：尺寸部位的默认测量方法），按 sort 排。
     * 不走缓存：缓存里的 DictOption 不带长文字；用它的只有工艺标准抽屉、资料包详情，量也小
     */
    List<DictData> listWithText(String typeCode);

    /** 只改几项的 text_value（工艺标准里改部位的测量方法）。texts：value → 新文字，空白存 null；类型下没有的 value 报 1000 */
    void updateTextValues(String typeCode, Map<String, String> texts);

    /** 只改几项的数值列（算料页改物料类型的备料比）。nums：value → 新数值；类型下没有的 value 报 1000。数值不进缓存，不用清 */
    void updateNumValues(String typeCode, Map<String, BigDecimal> nums);
}