package com.lingmao.scm.module.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 一份资质证照。fileUrl 已经签过名 */
public record CertVO(Long id,
                     String certType,
                     String fileUrl,
                     String fileName,
                     LocalDate validUntil,
                     String remark,
                     String createByName,
                     @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
                     OffsetDateTime createTime) {
}
