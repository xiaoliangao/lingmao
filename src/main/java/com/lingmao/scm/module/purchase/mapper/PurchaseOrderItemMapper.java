package com.lingmao.scm.module.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Mapper
public interface PurchaseOrderItemMapper extends BaseMapper<PurchaseOrderItem> {

    @Select("SELECT * FROM pur_order_item WHERE purchase_order_id = #{poId} ORDER BY seq, id")
    List<PurchaseOrderItem> selectByOrder(@Param("poId") long poId);

    /** 几张单的明细（导出） */
    @Select("<script>SELECT * FROM pur_order_item WHERE purchase_order_id IN "
            + "<foreach collection='poIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY purchase_order_id, seq, id</script>")
    List<PurchaseOrderItem> selectByOrders(@Param("poIds") Collection<Long> poIds);

    @Delete("DELETE FROM pur_order_item WHERE purchase_order_id = #{poId}")
    void deleteByOrder(@Param("poId") long poId);

    /** 入库、退货过账回写（调之前已经锁住采购单、查过上限） */
    @Update("UPDATE pur_order_item SET received_qty = received_qty + #{received}, returned_qty = returned_qty + #{returned} "
            + "WHERE id = #{id}")
    void addPosted(@Param("id") long id, @Param("received") BigDecimal received, @Param("returned") BigDecimal returned);

    /** 改预计到货、跟进备注（不碰单据版本） */
    @Update("UPDATE pur_order_item SET expect_date = #{expectDate}, follow_note = #{followNote} WHERE id = #{id}")
    void updateFollow(@Param("id") long id, @Param("expectDate") LocalDate expectDate, @Param("followNote") String followNote);

    /** 重新算料的新单确认：上一张这一行生成的采购行改指新单对应的行（先用 selectPoIdsOfCalcItem 取涉及的采购单） */
    @Update("UPDATE pur_order_item SET calc_item_id = #{to} WHERE calc_item_id = #{from}")
    void moveCalcItem(@Param("from") long from, @Param("to") long to);

    @Select("SELECT DISTINCT purchase_order_id FROM pur_order_item WHERE calc_item_id = #{calcItemId}")
    List<Long> selectPoIdsOfCalcItem(@Param("calcItemId") long calcItemId);

    @Select("<script>SELECT DISTINCT purchase_order_id FROM pur_order_item WHERE calc_item_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY purchase_order_id</script>")
    List<Long> selectPoIdsOfCalcItems(@Param("ids") Collection<Long> ids);

    /** 指着这些算料行的采购行（没作废的采购单）：算料单详情的"已入库"、齐料 */
    @Select("<script>SELECT i.* FROM pur_order_item i JOIN pur_order o ON o.id = i.purchase_order_id "
            + "WHERE o.status &lt;&gt; 'VOIDED' AND i.calc_item_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<PurchaseOrderItem> selectByCalcItems(@Param("ids") Collection<Long> ids);

    /** 采购行的来源算料行所在的算料单（改、删、作废采购单前先锁它们） */
    @Select("<script>SELECT DISTINCT ci.calc_id FROM mrp_calc_item ci WHERE ci.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectCalcIdsOfItems(@Param("ids") Collection<Long> calcItemIds);

    /** 采购行的单号、类型（算料单详情列本次使用在途、赶不上的在途） */
    record LineRef(Long id, Long poId, String poNo, String purchaseType) {
    }

    @Select("<script>SELECT i.id, o.id AS po_id, o.no AS po_no, o.purchase_type FROM pur_order_item i "
            + "JOIN pur_order o ON o.id = i.purchase_order_id WHERE i.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<LineRef> selectLineRefs(@Param("ids") Collection<Long> ids);

    /** 采购单详情里每行的来源：算料行所在的单号、订单号、款号 */
    record CalcSource(Long calcItemId, Long calcId, String calcNo, String orderNos, String styleCodes) {
    }

    @Select("<script>SELECT ci.id AS calc_item_id, c.id AS calc_id, c.no AS calc_no, ci.order_nos, ci.style_codes "
            + "FROM mrp_calc_item ci JOIN mrp_calc c ON c.id = ci.calc_id WHERE ci.id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<CalcSource> selectCalcSources(@Param("ids") Collection<Long> calcItemIds);

    /** 当前已授权采购页的来源摘要；各类编号先在 SQL 内限为 20 个，再聚合为 CSV。 */
    record PendingSourcePreview(Long lineId, String calcNo, long sourcesTotal, String orderNos,
                                long orderNosTotal, String styleCodes, long styleCodesTotal) {
    }

    List<PendingSourcePreview> selectPendingSourcePreviews(@Param("lineIds") Collection<Long> lineIds,
                                                          @Param("limit") int limit);
}
