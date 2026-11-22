package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.production.dto.IssueOptionQuery;
import com.lingmao.scm.module.production.dto.IssueOptionVO;
import com.lingmao.scm.module.production.dto.ProductionQuery;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.dto.ProductionStockDocVO;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 生产单（XML 在 mapper/production/ProductionOrderMapper.xml）。加工厂、订单、款式、资料包 BOM、物料出入库单、库存、变更提醒只读 JOIN（架构 §3.2 规则 4）。
 * 列表、条数、看板都带 today、nearUntil（今天 + 临期天数）现算货期预警
 */
@Mapper
public interface ProductionOrderMapper extends BaseMapper<ProductionOrder> {

    /** 改、删、动作、回写之前锁住单头。要回写订单的先锁订单（OrderService.lockForProduction）再锁它 */
    @Select("SELECT * FROM prd_order WHERE id = #{id} FOR UPDATE")
    ProductionOrder lockById(@Param("id") long id);

    IPage<ProductionRowVO> selectRowPage(IPage<ProductionRowVO> page, @Param("q") ProductionQuery query, @Param("codes") List<String> codes,
                                         @Param("scope") DataScope scope, @Param("today") LocalDate today,
                                         @Param("nearUntil") LocalDate nearUntil);

    /** 导出、手机"我的跟单"：不分页 */
    List<ProductionRowVO> selectRows(@Param("q") ProductionQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope,
                                     @Param("today") LocalDate today, @Param("nearUntil") LocalDate nearUntil);

    /** 各状态几张，partial 是其中回过良品的（只有 RUNNING 有用）；条件和列表一样，不看 status、returned */
    List<CountRow> selectCounts(@Param("q") ProductionQuery query, @Param("codes") List<String> codes, @Param("scope") DataScope scope,
                                @Param("today") LocalDate today, @Param("nearUntil") LocalDate nearUntil);

    record CountRow(String status, long count, long partial) {
    }

    /** 详情要的订单号、款式品牌（只读 JOIN） */
    @Select("SELECT o.no AS order_no, s.brand FROM prd_order p JOIN ord_order o ON o.id = p.order_id "
            + "JOIN sty_style s ON s.id = p.style_id WHERE p.id = #{id}")
    DetailRefs selectDetailRefs(@Param("id") long id);

    record DetailRefs(String orderNo, String brand) {
    }

    /** 一张单的货期预警，和列表同一段 SQL（详情用） */
    RiskRow selectRisk(@Param("id") long id, @Param("today") LocalDate today, @Param("nearUntil") LocalDate nearUntil);

    record RiskRow(boolean late, boolean near, boolean nodeLate, boolean issue, boolean upstream) {
    }

    /** 看板数字卡（只看已下单、生产中），条件同列表，不看 card */
    CardRow selectCards(@Param("q") ProductionQuery query, @Param("scope") DataScope scope, @Param("today") LocalDate today,
                        @Param("nearUntil") LocalDate nearUntil);

    record CardRow(long runningCount, long runningQty, long waitingCount, long waitingQty, long nearCount, long lateCount,
                   long nodeLateCount, long issueCount) {
    }

    /** 看板"本月回货"：这段日期里登记、没作废的回货单件数，按种类、加工厂、跟单、品牌、数据范围筛 */
    long selectReturnedQty(@Param("q") ProductionQuery query, @Param("scope") DataScope scope, @Param("from") LocalDate from,
                           @Param("to") LocalDate to);

    /**
     * 看板按工厂：合作中的成衣厂、加工厂都列，加上停用了但还有在制的；在制 = 已下单、生产中的单数和件数（件数 − 已回良品）。
     * 按在制件数从多到少
     */
    List<FactoryLoadRow> selectFactoryLoads(@Param("q") ProductionQuery query, @Param("scope") DataScope scope, @Param("today") LocalDate today,
                                            @Param("nearUntil") LocalDate nearUntil, @Param("categories") Collection<String> factoryCategories);

    record FactoryLoadRow(Long factoryId, String factoryShortName, long wipCount, long wipQty, long lateCount) {
    }

    /** 给 04 选生产单（docs/api/05 §3.4.2） */
    IPage<IssueOptionVO> selectIssueOptions(IPage<IssueOptionVO> page, @Param("q") IssueOptionQuery query, @Param("codes") List<String> codes,
                                            @Param("scope") DataScope scope);

    /**
     * 应领的原料：锁定的生产 BOM 里物料来源 = 自购的行，每个明细 SKU 对上的物料 SKU、件数、单件用量、损耗率（和算料 MrpCalcMapper.selectDemand
     * 同一种对法）。按尺码配用量但这个尺码没填的，usage 是 null
     */
    List<NeedRow> selectNeeds(@Param("ids") Collection<Long> prdOrderIds);

    record NeedRow(long prdOrderId, long skuId, int qty, BigDecimal usage, BigDecimal lossPct) {
    }

    /**
     * 已领 = 已审核的领料出库 − 退料入库（库存单位），按生产单、物料 SKU。excludeDocId 不算这张（退料审核时它已经是已审核，查上限要扣掉它）
     */
    List<SkuQtyRow> selectIssued(@Param("ids") Collection<Long> prdOrderIds, @Param("excludeDocId") Long excludeDocId);

    record SkuQtyRow(long prdOrderId, long skuId, BigDecimal qty) {
    }

    /** 物料仓（启用的）的库存合计，自有库存 */
    @Select("<script>SELECT s.material_sku_id AS sku_id, SUM(s.qty) AS qty FROM inv_stock s JOIN bas_warehouse w ON w.id = s.warehouse_id "
            + "WHERE w.type = 'MATERIAL' AND w.status = 1 AND s.owner_type = 'OWN' AND s.material_sku_id IN "
            + "<foreach collection='skuIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY s.material_sku_id</script>")
    List<StockRow> selectMaterialStock(@Param("skuIds") Collection<Long> skuIds);

    record StockRow(long skuId, BigDecimal qty) {
    }

    /** 挂在生产单上的领料出库单、退料入库单：草稿也列，作废的不列，新的在前 */
    List<ProductionStockDocVO> selectStockDocs(@Param("id") long id);

    /** 挂在生产单上的物料出入库单几张（没作废的） */
    @Select("SELECT COUNT(*) FROM inv_doc WHERE source_type = 'PRODUCTION_ORDER' AND source_id = #{id} AND status <> 'VOIDED'")
    int countStockDocs(@Param("id") long id);

    /** 几张订单从物料仓已领（它们的生产单上已审核的领料出库 − 退料入库，库存单位），回写算料单用 */
    @Select("<script>SELECT p.order_id, i.material_sku_id AS sku_id, "
            + "SUM(CASE WHEN d.doc_type = 'ISSUE_OUT' THEN i.qty ELSE -i.qty END) AS qty "
            + "FROM inv_doc d JOIN inv_doc_item i ON i.doc_id = d.id JOIN prd_order p ON p.id = d.source_id "
            + "WHERE d.source_type = 'PRODUCTION_ORDER' AND d.status = 'APPROVED' AND d.doc_type IN ('ISSUE_OUT', 'RETURN_IN') "
            + "AND p.order_id IN <foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY p.order_id, i.material_sku_id</script>")
    List<OrderSkuQtyRow> selectIssuedByOrders(@Param("orderIds") Collection<Long> orderIds);

    record OrderSkuQtyRow(long orderId, long skuId, BigDecimal qty) {
    }

    /**
     * 这几张订单里料都领完了的：每行都排满了（已排产 ≥ 订单数）、有没作废的生产单、没作废没结案的生产单都领齐了（或者不用领料）。
     * 它们的算料单可以自动关闭（03 的 ISSUED）
     */
    @Select("<script>SELECT o.id FROM ord_order o WHERE o.id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "AND NOT EXISTS (SELECT 1 FROM ord_order_item i WHERE i.order_id = o.id AND i.scheduled_qty &lt; i.qty) "
            + "AND EXISTS (SELECT 1 FROM prd_order p WHERE p.order_id = o.id AND p.status &lt;&gt; 'VOIDED') "
            + "AND NOT EXISTS (SELECT 1 FROM prd_order p WHERE p.order_id = o.id AND p.status NOT IN ('VOIDED', 'CLOSED') "
            + "AND p.issue_status IN ('NONE', 'PARTIAL'))</script>")
    List<Long> selectIssuedOrders(@Param("orderIds") Collection<Long> orderIds);

    /** 回写领料状态：动作改，不走实体（issue_status 是 updateStrategy = NEVER） */
    @Update("UPDATE prd_order SET issue_status = #{status} WHERE id = #{id}")
    void updateIssueStatus(@Param("id") long id, @Param("status") String status);

    /** 上报异常填了新交期：改预计交期，原交期不动 */
    @Update("UPDATE prd_order SET expect_date = #{date}, update_time = now() WHERE id = #{id}")
    void updateExpectDate(@Param("id") long id, @Param("date") LocalDate date);

    @Update("UPDATE prd_order SET plan_revision=plan_revision+1, update_time=now() WHERE id=#{id} AND plan_revision=#{revision}")
    int advancePlan(@Param("id") long id, @Param("revision") int revision);

    /** 一张订单的生产单（不含作废的），锁订单以后查：订单的生产进度、领料齐不齐 */
    @Select("SELECT * FROM prd_order WHERE order_id = #{orderId} AND status <> 'VOIDED' ORDER BY id")
    List<ProductionOrder> selectLiveByOrder(@Param("orderId") long orderId);

    /** 几张订单的要料日期：已下单、生产中的生产单里"裁剪"节点最早的计划开始（03 的 NeedDateSource） */
    @Select("<script>SELECT p.order_id, MIN(n.plan_start) AS need_date FROM prd_order p JOIN prd_node n ON n.prd_order_id = p.id "
            + "WHERE p.status IN ('APPROVED', 'RUNNING') AND n.node_code = #{cut} AND p.order_id IN "
            + "<foreach collection='orderIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> GROUP BY p.order_id</script>")
    List<NeedDateRow> selectNeedDates(@Param("orderIds") Collection<Long> orderIds, @Param("cut") String cutNodeCode);

    record NeedDateRow(long orderId, LocalDate needDate) {
    }
}
