package com.lingmao.scm.module.production.dto;

import lombok.Data;

/** 给 04 选生产单（GET /api/production-orders/issue-options，前端 IssueOption） */
@Data
public class IssueOptionVO {
    private Long id;
    private String no;
    private String kind;
    private String status;
    private Long factoryId;
    private String factoryShortName;
    private String styleCode;
    private String styleName;
    private int totalQty;
    private String issueStatus;
}
