package com.lingmao.scm.module.quality.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.quality.dto.QcCreatedVO;
import com.lingmao.scm.module.quality.dto.QcOrderOption;
import com.lingmao.scm.module.quality.dto.QcPendingQuery;
import com.lingmao.scm.module.quality.dto.QcPendingRow;
import com.lingmao.scm.module.quality.dto.QcQuery;
import com.lingmao.scm.module.quality.dto.QcReq;
import com.lingmao.scm.module.quality.dto.QcReworkReq;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.dto.QcStatQuery;
import com.lingmao.scm.module.quality.dto.QcSupplierStat;
import com.lingmao.scm.module.quality.entity.QcKind;

import java.util.List;

/** 质检（RFC 019 §6，docs/api/10） */
public interface QcInspectionService {

    PageResult<QcRowVO> page(QcQuery query);

    QcRowVO detail(long id);

    /** 待检：最近 30 天已审核还没验的采购入库单、回货单，加上返修完成等再验的回货单 */
    PageResult<QcPendingRow> pending(QcPendingQuery query);

    /** 尾期验货不合格、还没返修完的 */
    List<QcRowVO> reworks(Long companyId);

    /** 记质检前看来源单：批量、AQL 建议、前几次的检验；现在不能验的报 1000（说为什么、怎么办） */
    QcSourceVO source(QcKind kind, long sourceId);

    /** 记产前样、中检时选生产单 */
    List<QcOrderOption> orderOptions(String keyword);

    /** 供应商质量：按家统计检验次数、结论、问题类型、扣款 */
    List<QcSupplierStat> supplierStats(QcStatQuery query);

    QcCreatedVO create(QcReq req);

    /** 改，返回新的 version */
    int update(long id, QcReq req);

    void delete(long id);

    /** 尾期验货不合格：拉回工厂返修，写返修说明；返回新的 version */
    int rework(long id, QcReworkReq req);

    /** 返修完成：这张回货单回到待检，再验一次；返回新的 version */
    int reworkDone(long id, QcReworkReq req);

    /** 首页提醒：待检的回货和来料 */
    long pendingCount();

    /** 首页提醒：回货验货不合格、还没安排返修 */
    long reworkPendingCount();
}
