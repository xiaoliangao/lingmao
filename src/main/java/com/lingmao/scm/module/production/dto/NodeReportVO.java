package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一次报工（前端 NodeReport）；汇报记录新的在前 */
public record NodeReportVO(Long id,
                           String action,
                           LocalDate actualStart,
                           LocalDate actualFinish,
                           Integer doneQty,
                           String remark,
                           List<AttachmentVO> photos,
                           String createByName,
                           @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai") OffsetDateTime createTime) {
}
