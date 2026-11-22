package com.lingmao.scm.module.biz.dto;

import com.lingmao.scm.module.biz.entity.BizType;
import java.time.OffsetDateTime;

/** 已验证游标的位置；时间、类型、ID 构成稳定的全序。 */
public record PendingNoticePosition(OffsetDateTime latest, BizType docType, long docId) { }
