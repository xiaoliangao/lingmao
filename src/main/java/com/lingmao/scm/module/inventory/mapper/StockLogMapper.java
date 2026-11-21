package com.lingmao.scm.module.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.inventory.dto.StockLogQuery;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockMovementQuery;
import com.lingmao.scm.module.inventory.dto.StockMovementRowVO;
import com.lingmao.scm.module.inventory.dto.PurchasePostingHistory;
import com.lingmao.scm.module.inventory.entity.StockLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Mapper
public interface StockLogMapper extends BaseMapper<StockLog> {
    @Select("SELECT COALESCE(max(id),0) FROM inv_stock_log")
    long exportWatermark();

    List<StockLogRowVO> selectExportBatch(@Param("q") StockLogQuery query, @Param("codes") List<String> codes,
            @Param("code") String code, @Param("watermark") long watermark,
            @Param("afterTime") OffsetDateTime afterTime, @Param("afterId") Long afterId, @Param("limit") int limit);

    /** 每个明细/仓库键最近尚未冲销的事件；库存单头CAS锁保证同单不会并发反审。 */
    @Select("<script>SELECT DISTINCT ON (l.warehouse_id, l.material_sku_id, l.inv_doc_item_id, l.count_item_id) l.* "
            + "FROM inv_stock_log l WHERE "
            + "<choose><when test='docId != null'>l.inv_doc_id = #{docId}</when><otherwise>l.count_id = #{countId}</otherwise></choose> "
            + "AND l.reversal_of_log_id IS NULL AND NOT EXISTS (SELECT 1 FROM inv_stock_log r WHERE r.reversal_of_log_id = l.id) "
            + "ORDER BY l.warehouse_id, l.material_sku_id, l.inv_doc_item_id, l.count_item_id, l.id DESC</script>")
    List<StockLog> reversibleOf(@Param("docId") Long docId, @Param("countId") Long countId);

    /** 采购单锁已持有；只按未冲销原事件聚合，历史已反审草稿后来修改也不会污染本次累计。 */
    @Select("<script>SELECT i.source_item_id, i.material_sku_id, l.change_type, i.purchase_qty, "
            + "i.conversion_factor, i.purchase_price, l.change_qty AS quantity, l.amount, "
            + "l.purchase_rounding_version AS rounding_version "
            + "FROM inv_stock_log l JOIN inv_doc d ON d.id=l.inv_doc_id JOIN inv_doc_item i ON i.id=l.inv_doc_item_id "
            + "WHERE d.source_type='PURCHASE_ORDER' AND d.source_id=#{sourceId} "
            + "AND i.source_item_id IN <foreach collection='sourceItems' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND l.change_type IN ('PURCHASE_IN','PURCHASE_RETURN') AND l.reversal_of_log_id IS NULL "
            + "AND NOT EXISTS (SELECT 1 FROM inv_stock_log r WHERE r.reversal_of_log_id=l.id) ORDER BY l.id</script>")
    List<PurchasePostingHistory> purchaseHistory(@Param("sourceId") long sourceId, @Param("sourceItems") Collection<Long> sourceItems);

    /** 这张入库单 / 出库单过过账没有（审核过一次就有日志，反审核也不删） */
    @Select("SELECT EXISTS (SELECT 1 FROM inv_stock_log WHERE inv_doc_id = #{docId})")
    boolean docPosted(@Param("docId") long docId);

    /** 几张入库单 / 出库单里过过账的（列表上决定给"删除"还是"作废"） */
    @Select("<script>SELECT DISTINCT inv_doc_id FROM inv_stock_log WHERE inv_doc_id IN "
            + "<foreach collection='docIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> postedDocs(@Param("docIds") Collection<Long> docIds);

    /** 过过账的明细行：被日志指着，删不掉 */
    @Select("SELECT DISTINCT inv_doc_item_id FROM inv_stock_log WHERE inv_doc_id = #{docId}")
    Set<Long> postedDocItems(@Param("docId") long docId);

    @Select("SELECT EXISTS (SELECT 1 FROM inv_stock_log WHERE count_id = #{countId})")
    boolean countPosted(@Param("countId") long countId);

    @Select("<script>SELECT DISTINCT count_id FROM inv_stock_log WHERE count_id IN "
            + "<foreach collection='countIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    Set<Long> postedCounts(@Param("countIds") Collection<Long> countIds);

    @Select("SELECT DISTINCT count_item_id FROM inv_stock_log WHERE count_id = #{countId}")
    Set<Long> postedCountItems(@Param("countId") long countId);

    /** 盘点单审核过的调整量：count_item_id → 合计（反审核过的一正一负抵掉）。没审核过的单是空的 */
    @Select("SELECT count_item_id, SUM(change_qty) AS change_qty FROM inv_stock_log WHERE count_id = #{countId} GROUP BY count_item_id")
    List<StockLog> sumByCountItem(@Param("countId") long countId);

    /** 取账面数以后，这个仓这些 SKU 有几笔别的单的进出（盘点审核时提示，PRD §6.6.8 规则 2） */
    @Select("<script>SELECT COUNT(*) FROM inv_stock_log WHERE warehouse_id = #{warehouseId} AND create_time &gt; #{since} "
            + "AND (count_id IS NULL OR count_id != #{countId}) AND material_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    int countMovesSince(@Param("warehouseId") long warehouseId, @Param("skuIds") Collection<Long> skuIds,
                        @Param("since") OffsetDateTime since, @Param("countId") long countId);

    /** 库存日志（XML）；codes 是关键字里的几个单号 / SKU 码（Keywords） */
    IPage<StockLogRowVO> selectRowPage(IPage<StockLogRowVO> page, @Param("q") StockLogQuery query, @Param("codes") List<String> codes);

    /** 进销存汇总（XML，RFC 016 §4）：从库存日志现算，一个 SKU 一行，期初、入、出、期末全是 0 的不列 */
    IPage<StockMovementRowVO> selectMovementPage(IPage<StockMovementRowVO> page, @Param("q") StockMovementQuery query,
                                                 @Param("codes") List<String> codes);

    /** 一次只取 size+1，不执行 COUNT，不使用 OFFSET。 */
    List<StockLogRowVO> selectSeek(@Param("q") StockLogQuery query, @Param("codes") List<String> codes,
                                   @Param("code") String code, @Param("afterTime") OffsetDateTime afterTime,
                                   @Param("afterId") Long afterId, @Param("limit") int limit);

}
