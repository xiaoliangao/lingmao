package com.lingmao.scm.module.techpack.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/** 一个没生效的资料包草稿（首页概况的"资料包草稿"、待我审核里的"草稿待生效"，docs/api/06 §2.2、§2.3） */
@Data
public class PackDraftVO {
    private Long id;
    private Long styleId;
    private String styleCode;
    private String styleName;
    private Integer version;
    private Long createBy;
    private OffsetDateTime createTime;
}
