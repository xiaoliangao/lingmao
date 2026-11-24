package com.lingmao.scm.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.purchase.dto.PendingArrivalQuery;
import com.lingmao.scm.module.purchase.dto.PendingArrivalRowVO;
import com.lingmao.scm.module.purchase.dto.PendingArrivalCounts;
import com.lingmao.scm.module.purchase.dto.PlanStyleVO;
import com.lingmao.scm.module.purchase.dto.PurchaseQuery;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.dto.PurchaseStockDocVO;
import com.lingmao.scm.module.purchase.entity.PurchaseOrder;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;
import java.time.LocalDate;

/** 采购单（XML 在 mapper/purchase/PurchaseOrderMapper.xml）。供应商、仓库、主体、物料、入库单只读 JOIN（架构 §3.2 规则 4） */
@Mapper
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    /** 改、删、动作、过账回写之前锁住单头 */
    @Select("SELECT * FROM pur_order WHERE id = #{id} FOR UPDATE")
    PurchaseOrder lockById(@Param("id") long id);

    /**
     * 算料单确认时共享锁住它要用的在途所在的采购单：和反审核（FOR UPDATE）排队，
     * 不会出现"确认时还是已审核、提交时已经反审核回草稿"
     */
    @Select("<script>SELECT id FROM pur_order WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id FOR SHARE</script>")
    List<Long> lockShared(@Param("ids") Collection<Long> ids);

    /** MRP确认含采购行改挂：预先按头ID取排他锁，避免先锁明细或共享锁升级形成环。 */
    @Select("<script>SELECT id FROM pur_order WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id FOR UPDATE</script>")
    List<Long> lockForUpdate(@Param("ids") Collection<Long> ids);

    IPage<PurchaseRowVO> selectRowPage(IPage<PurchaseRowVO> page, @Param("q") PurchaseQuery query, @Param("codes") List<String> codes,
                                       @Param("scope") DataScope scope);

    /** 预算预检只保留主键和主体，不读取整张头 DTO 或显示关联。 */
    record ExportHead(Long id, Long companyId) { }
    List<ExportHead> selectExportHeads(@Param("q") PurchaseQuery query, @Param("codes") List<String> codes,
                                       @Param("scope") DataScope scope, @Param("limit") int limit);

    /** 导出：和列表一样的条件，不分页（PurchaseQuery.ids 有值时只导这几张） */
    List<PurchaseRowVO> selectRows(@Param("q") PurchaseQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope);

    /** 原始待到料投影，供受控批量处理与索引验证；HTTP 列表必须使用 selectPendingPage。 */
    List<PendingArrivalRowVO> selectPendingRows(@Param("q") PendingArrivalQuery query, @Param("codes") List<String> codes,
                                                @Param("scope") DataScope scope);

    /** 待到料的权限、页签和排序先在数据库执行，再返回当前页；日期由业务 Clock 决定。 */
    IPage<PendingArrivalRowVO> selectPendingPage(IPage<PendingArrivalRowVO> page, @Param("q") PendingArrivalQuery query,
                                                @Param("codes") List<String> codes, @Param("scope") DataScope scope,
                                                @Param("today") LocalDate today, @Param("fallbackLeadDays") Integer fallbackLeadDays);

    /** 页签数量与分页查询解耦，范围一致但不使用 q.tab。 */
    PendingArrivalCounts selectPendingCounts(@Param("q") PendingArrivalQuery query, @Param("codes") List<String> codes,
                                              @Param("scope") DataScope scope, @Param("today") LocalDate today,
                                              @Param("fallbackLeadDays") Integer fallbackLeadDays);

    @Select("SELECT p.style_id, s.code AS style_code, s.name AS style_name FROM pur_order_style p JOIN sty_style s ON s.id = p.style_id "
            + "WHERE p.purchase_order_id = #{id} ORDER BY s.code")
    List<PlanStyleVO> selectPlanStyles(@Param("id") long id);

    @Insert("<script>INSERT INTO pur_order_style (purchase_order_id, style_id) VALUES "
            + "<foreach collection='styleIds' item='s' separator=','>(#{id}, #{s})</foreach></script>")
    void insertPlanStyles(@Param("id") long id, @Param("styleIds") Collection<Long> styleIds);

    @Delete("DELETE FROM pur_order_style WHERE purchase_order_id = #{id}")
    void deletePlanStyles(@Param("id") long id);

    /** 这张单生成的物料入库单、出库单（作废的不列） */
    List<PurchaseStockDocVO> selectStockDocs(@Param("id") long id);

    /** 还没审核的入库单（结案前要先处理掉） */
    @Select("SELECT no FROM inv_doc WHERE source_type = 'PURCHASE_ORDER' AND source_id = #{id} AND kind = 'IN' "
            + "AND status IN ('DRAFT', 'PENDING') ORDER BY id")
    List<String> selectOpenInDocs(@Param("id") long id);

    /** 补货提醒：每个 SKU 的最新可见备货单预览及完整可见单数。 */
    record OpenDraftPreview(Long skuId, Long id, String no, String status, long total) {
    }

    List<OpenDraftPreview> selectOpenDraftPreviews(@Param("skuIds") Collection<Long> skuIds,
                                                 @Param("scope") DataScope scope, @Param("limit") int limit);

    /** 算料单生成的采购单（行指着它的算料行；没作废的）：算料单的"下游单据" */
    @Select("SELECT * FROM pur_order o WHERE o.status <> 'VOIDED' AND EXISTS (SELECT 1 FROM pur_order_item i "
            + "JOIN mrp_calc_item ci ON ci.id = i.calc_item_id WHERE i.purchase_order_id = o.id AND ci.calc_id = #{calcId}) ORDER BY o.id")
    List<PurchaseOrder> selectByCalc(@Param("calcId") long calcId);

    /** 重新算料转了采购行的采购单：版本号 +1，开着旧表单的人保存时会被拦（行上的来源算料行变了） */
    @Update("<script>UPDATE pur_order SET version = version + 1 WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    void bumpVersions(@Param("ids") Collection<Long> ids);
}
