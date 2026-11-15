package com.lingmao.scm.module.basic.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 主数据上存字典 value 的列（物料类型、单位、季节、色系……）保存前查一下：新填的值只认启用的选项（RFC 003 §2"停用"）；
 * 修改时没动的旧值，选项后来停用了也照样留着，不逼着人改。不合法返回 400
 */
@Component
@RequiredArgsConstructor
class DictChecks {

    private final DictDataService dictDataService;

    /**
     * @param value    要存的值，空的不查（必填由 DTO 管）
     * @param previous 修改前的值；新建传 null
     * @param what     列的中文名，拼进提示
     */
    void require(String typeCode, String value, String previous, String what) {
        if (value == null || Objects.equals(value, previous)) {
            return;
        }
        List<DictOption> options = dictDataService.listByType(typeCode);
        boolean ok = options.stream().anyMatch(o -> o.enabled() && o.value().equals(value));
        if (!ok) {
            throw new BizException(400, what + "「" + value + "」不在字典里，或者已经停用了");
        }
    }
}
