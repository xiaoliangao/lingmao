package com.lingmao.scm.module.supplier.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一份关键字段改动（RFC 004 §4）。items 里的 before、after 是给人看的文字：字典翻成中文，税率带 %，账期带"天"，空的是"空"；
 * 账号只有有 bank 权限的人看得到全号，别人看到遮过的。
 * actions：当前用户能对它做的（approve、reject、withdraw），只有待批的才有
 */
public record ChangeVO(Long id,
                       ChangeStatus status,
                       List<ChangeItem> items,
                       Long applyBy,
                       String applyByName,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                       OffsetDateTime applyTime,
                       String auditByName,
                       @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
                       OffsetDateTime auditTime,
                       String rejectReason,
                       List<String> actions) {
}
