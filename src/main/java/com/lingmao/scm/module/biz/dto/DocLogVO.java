package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * 操作记录的一行，详情页"操作记录"页签显示它。action、fromStatus、toStatus 是英文名，
 * 页面按单据类型翻成中文（采购单的 RUNNING 叫"部分到货"）。
 * operatorName 是操作人的昵称，人删了也照样有；系统做的（没有操作人）是空
 */
public record DocLogVO(Long id,
                       String action,
                       String fromStatus,
                       String toStatus,
                       String remark,
                       Long operatorId,
                       String operatorName,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                       OffsetDateTime createTime) {
}
