package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一条异常（前端 ProductionIssue）；按钮 start（待处理才有）、close（没关闭的都有），都要 production:issue:edit */
@Data
public class ProductionIssueVO {
    private Long id;
    private String no;
    private String issueType;
    private String description;
    private List<AttachmentVO> photos = List.of();
    private boolean impactDelivery;
    private LocalDate newExpectDate;
    private String responsibleParty;
    private String status;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private String handlerName;
    private String measure;
    private String closeByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime closeTime;
    private List<String> actions = List.of();
}
