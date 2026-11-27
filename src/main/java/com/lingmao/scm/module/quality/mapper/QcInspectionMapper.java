package com.lingmao.scm.module.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.quality.dto.QcOrderOption;
import com.lingmao.scm.module.quality.dto.QcPendingQuery;
import com.lingmao.scm.module.quality.dto.QcPendingRow;
import com.lingmao.scm.module.quality.dto.QcQuery;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.dto.QcStatQuery;
import com.lingmao.scm.module.quality.dto.QcSupplierStat;
import com.lingmao.scm.module.quality.entity.QcInspection;
import com.lingmao.scm.security.LineScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 质检记录（RFC 019 §6）。来源单（采购入库单、回货单、生产单）、供应商、主体、扣款都是 XML 里只读 JOIN（架构 §3.2 规则 4 的例外）。
 * 品类线：来料检验不分线（物料出入库单不分），生产单的按 product_line 过滤
 */
@Mapper
public interface QcInspectionMapper extends BaseMapper<QcInspection> {

    IPage<QcRowVO> selectRowPage(IPage<QcRowVO> page, @Param("q") QcQuery query, @Param("lines") LineScope lines);

    QcRowVO selectRow(@Param("id") long id);

    /** 一张来源单同一种检验的全部记录，早的在前 */
    List<QcRowVO> selectHistory(@Param("kind") String kind, @Param("sourceId") long sourceId);

    /** 尾期验货不合格、还没返修完的（不合格待安排返修、返修中），早的在前 */
    List<QcRowVO> selectReworks(@Param("companyId") Long companyId, @Param("lines") LineScope lines);

    /** 待检：since 以后已审核还没验的采购入库单、回货单，加上返修完成等再验的回货单（不管日期） */
    IPage<QcPendingRow> selectPending(IPage<QcPendingRow> page, @Param("q") QcPendingQuery query,
                                      @Param("since") LocalDate since, @Param("lines") LineScope lines);

    /** 待检的条数（首页提醒） */
    long countPending(@Param("since") LocalDate since, @Param("lines") LineScope lines);

    /** 不合格还没安排返修的回货（首页提醒） */
    long countReworkPending(@Param("lines") LineScope lines);

    /** 来料检验的来源：采购入库单（不是采购入库的查不到）；sourceStatus 是单据状态 */
    QcSourceVO selectIncomingSource(@Param("id") long invDocId);

    /** 尾期验货的来源：回货单 */
    QcSourceVO selectReceiptSource(@Param("id") long receiptId);

    /** 产前样、中检的来源：生产单 */
    QcSourceVO selectOrderSource(@Param("id") long prdOrderId);

    /** 记产前样、中检时选生产单：已审核、生产中、已完成的，新的在前，最多 20 张 */
    List<QcOrderOption> selectOrderOptions(@Param("keyword") String keyword, @Param("lines") LineScope lines);

    List<QcSupplierStat> selectSupplierStats(@Param("q") QcStatQuery query, @Param("lines") LineScope lines);

    /** 每家的问题类型出现了几次：partner_id、defect_type、n */
    List<Map<String, Object>> selectDefectCounts(@Param("q") QcStatQuery query, @Param("lines") LineScope lines);

    @Select("SELECT * FROM qc_inspection WHERE id = #{id} FOR UPDATE")
    @ResultMap("mybatis-plus_QcInspection")
    QcInspection lockById(@Param("id") long id);

    /** 这张回货单最近一次尾期验货，锁住：和安排返修、返修完成、别人同时验排队 */
    /** 回货单还有效（已审核）：作废了的回货不用再返修（agy T-e035d4 #7） */
    @Select("SELECT EXISTS (SELECT 1 FROM prd_receipt WHERE id = #{receiptId} AND status = 'APPROVED')")
    boolean receiptApproved(@Param("receiptId") long receiptId);

    @Select("SELECT * FROM qc_inspection WHERE prd_receipt_id = #{receiptId} AND kind = 'FINAL' ORDER BY attempt_no DESC LIMIT 1 FOR UPDATE")
    @ResultMap("mybatis-plus_QcInspection")
    QcInspection lockLatestFinal(@Param("receiptId") long receiptId);

    @Select("SELECT COALESCE(MAX(attempt_no), 0) FROM qc_inspection WHERE prd_order_id = #{prdOrderId} AND kind = #{kind}")
    int maxOrderAttempt(@Param("prdOrderId") long prdOrderId, @Param("kind") String kind);

    @Select("SELECT no FROM qc_inspection WHERE inv_doc_id = #{invDocId} AND kind = 'INCOMING'")
    String incomingNo(@Param("invDocId") long invDocId);
}
