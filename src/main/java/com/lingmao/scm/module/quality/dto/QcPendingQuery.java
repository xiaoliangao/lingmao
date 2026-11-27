package com.lingmao.scm.module.quality.dto;

import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.module.quality.entity.QcKind;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 待检：最近 30 天已审核还没验的采购入库单、回货单，加上返修完成等再验的回货单 */
@Data
@EqualsAndHashCode(callSuper = true)
public class QcPendingQuery extends PageQuery {
    /** INCOMING 只看来料 / FINAL 只看回货；不传是全部 */
    private QcKind kind;
    private Long companyId;
    /** 来源单号、生产单号、供应商的编码名称简称、货品（包含） */
    private String keyword;
}
