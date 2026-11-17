package com.lingmao.scm.module.biz.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 处理一条上游变更提醒（POST …/{id}/notices/{noticeId}/handle）：怎么处理的，选填 */
@Data
public class NoticeHandleReq {

    @Size(max = 200, message = "处理说明最长 200 个字")
    private String remark;
}
