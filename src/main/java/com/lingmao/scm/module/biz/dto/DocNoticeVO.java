package com.lingmao.scm.module.biz.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.OffsetDateTime;

/**
 * 一条上游变更提醒（前端 types/biz.ts 的 DocNotice）：详情顶上的黄条点开、首页"上游已变更"都是它。
 * source 是引起它的单据（变更单），点了去那张单；处理了的带处理人、时间、说明，没处理的这三个是 null
 */
public record DocNoticeVO(Long id,
                          DocRef source,
                          String content,
                          @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                          OffsetDateTime createTime,
                          String handledByName,
                          @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                          OffsetDateTime handledTime,
                          String handleRemark) {
}
