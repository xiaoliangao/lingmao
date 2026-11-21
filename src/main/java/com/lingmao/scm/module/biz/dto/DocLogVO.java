package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 操作记录的一行，详情页"操作记录"页签显示它。action、fromStatus、toStatus 是英文名，
 * 页面按单据类型翻成中文（采购单的 RUNNING 叫"部分到货"）。
 * operatorName 是操作人的昵称，人删了也照样有；系统做的（没有操作人）是空。
 * diff 是档案"修改"改了哪些字段（RFC 016 §3），别的动作、以前的记录是空列表
 */
public record DocLogVO(Long id,
                       String action,
                       String fromStatus,
                       String toStatus,
                       String remark,
                       Long operatorId,
                       String operatorName,
                       @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
                       OffsetDateTime createTime,
                       List<FieldChangeVO> diff) {
}
