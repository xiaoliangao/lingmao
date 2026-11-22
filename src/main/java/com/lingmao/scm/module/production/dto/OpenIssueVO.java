package com.lingmao.scm.module.production.dto;

import lombok.Data;

import java.time.OffsetDateTime;

/** 一条没关闭的异常（首页"异常待处理"，docs/api/06 §2.3）：异常类型翻好了中文，上报人填好了昵称 */
@Data
public class OpenIssueVO {
    private Long id;
    private String no;
    private Long prdOrderId;
    private String prdOrderNo;
    private String kind;
    private Long styleId;
    private String styleName;
    private String issueType;
    private String issueTypeName;
    private String description;
    private Long createBy;
    private String createByName;
    private OffsetDateTime createTime;
}
