package com.lingmao.scm.module.system.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.system.entity.DictData;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 管理后台字典页的一行：字典项的列 + trade（贸易代工的保留值，TRADE 关时下拉里滤掉，字典页照常列出、标"代工"，RFC 003 §3.3）。
 * builtin 的不能删、不能停用；value 建好都不能改（RFC 003 §4.2）
 */
public record DictDataVO(Long id, String typeCode, String label, String value, Integer sort, Integer status, String remark,
                         BigDecimal numValue, String textValue, Boolean builtin, boolean trade,
                         @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
                         OffsetDateTime createTime) {

    public static DictDataVO of(DictData d, boolean trade) {
        return new DictDataVO(d.getId(), d.getTypeCode(), d.getLabel(), d.getValue(), d.getSort(), d.getStatus(), d.getRemark(),
                d.getNumValue(), d.getTextValue(), Boolean.TRUE.equals(d.getBuiltin()), trade, d.getCreateTime());
    }
}
