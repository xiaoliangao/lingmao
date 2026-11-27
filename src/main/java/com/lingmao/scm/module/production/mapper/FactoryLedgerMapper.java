package com.lingmao.scm.module.production.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.module.production.dto.LedgerOrderQuery;
import com.lingmao.scm.module.production.dto.LedgerOrderRowVO;
import com.lingmao.scm.security.DataScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/** 工厂料账（RFC 015）：耗料核销单、按生产单、按工厂物料的查询。耗料核销单是 inv_doc 的一种（doc_type = CONSUME_OUT），只读 */
@Mapper
public interface FactoryLedgerMapper {

    /** 挂在这些生产单上、没作废的耗料核销单（部分唯一索引保证一张单最多一张） */
    @Select("<script>SELECT source_id AS prd_order_id, id, no, status FROM inv_doc WHERE source_type = 'PRODUCTION_ORDER' "
            + "AND doc_type = 'CONSUME_OUT' AND status &lt;&gt; 'VOIDED' AND source_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<SettleDocRow> selectSettleDocs(@Param("ids") Collection<Long> prdOrderIds);

    record SettleDocRow(long prdOrderId, long id, String no, String status) {
    }

    /** 实耗：已审核的耗料核销单，生产单 → 物料 SKU → 数量 */
    @Select("<script>SELECT d.source_id AS prd_order_id, i.material_sku_id AS sku_id, SUM(i.qty) AS qty "
            + "FROM inv_doc d JOIN inv_doc_item i ON i.doc_id = d.id "
            + "WHERE d.source_type = 'PRODUCTION_ORDER' AND d.doc_type = 'CONSUME_OUT' AND d.status = 'APPROVED' AND d.source_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY d.source_id, i.material_sku_id</script>")
    List<ProductionOrderMapper.SkuQtyRow> selectSettled(@Param("ids") Collection<Long> prdOrderIds);

    /** 良品件数（已回 − 次品） */
    @Select("SELECT COALESCE(SUM(returned_qty - defect_qty), 0) FROM prd_order_item WHERE prd_order_id = #{id}")
    int goodQty(@Param("id") long prdOrderId);

    /** 按生产单（XML）：进料账的生产单，按料账状态、加工厂、种类、关键词筛 */
    IPage<LedgerOrderRowVO> selectOrders(IPage<LedgerOrderRowVO> page, @Param("q") LedgerOrderQuery q, @Param("scope") DataScope scope);

    /** 每种料账状态各几张（不按状态筛，别的条件照用） */
    List<StatusCount> selectCounts(@Param("q") LedgerOrderQuery q, @Param("scope") DataScope scope);

    record StatusCount(String status, long count) {
    }

    /** 待核销的生产单几张（首页提醒，不跟"只看我的"走）；范围和料账列表一样（品类线、角色的数据范围），点过去数对得上 */
    @Select("<script>SELECT COUNT(*) FROM prd_order p WHERE p.issue_status IS NOT NULL AND p.status IN ('DONE', 'CLOSED') "
            + "AND NOT EXISTS (SELECT 1 FROM inv_doc sd WHERE sd.source_type = 'PRODUCTION_ORDER' AND sd.source_id = p.id "
            + "AND sd.doc_type = 'CONSUME_OUT' AND sd.status &lt;&gt; 'VOIDED') "
            + "AND (EXISTS (SELECT 1 FROM inv_doc d JOIN inv_doc_item di ON di.doc_id = d.id "
            + "WHERE d.source_type = 'PRODUCTION_ORDER' AND d.source_id = p.id AND d.status = 'APPROVED' "
            + "AND d.doc_type IN ('ISSUE_OUT', 'RETURN_IN') "
            + "GROUP BY di.material_sku_id HAVING SUM(CASE WHEN d.doc_type = 'ISSUE_OUT' THEN di.qty ELSE -di.qty END) &lt;&gt; 0) "
            + "OR EXISTS (SELECT 1 FROM prd_order_item i WHERE i.prd_order_id = p.id AND i.returned_qty &gt; i.defect_qty)) "
            + "<if test='scope.self'>AND (p.create_by = #{scope.userId} OR p.merch_id = #{scope.userId})</if> "
            + "<if test='scope.lines.limited'>AND p.product_line = ANY(#{scope.lines.values, "
            + "typeHandler=com.lingmao.scm.common.mybatis.StringArrayTypeHandler})</if></script>")
    long countToSettle(@Param("scope") DataScope scope);

    /** 还没核销的生产单（在制、待核销、核销中）：id、加工厂。factoryId 是 null 时全部工厂 */
    @Select("<script>SELECT p.id, p.factory_id FROM prd_order p WHERE p.issue_status IS NOT NULL "
            + "AND p.status IN ('APPROVED', 'RUNNING', 'DONE', 'CLOSED') "
            + "AND NOT EXISTS (SELECT 1 FROM inv_doc sd WHERE sd.source_type = 'PRODUCTION_ORDER' AND sd.source_id = p.id "
            + "AND sd.doc_type = 'CONSUME_OUT' AND sd.status = 'APPROVED') "
            + "<if test='factoryId != null'>AND p.factory_id = #{factoryId}</if></script>")
    List<OpenOrderRow> selectOpenOrders(@Param("factoryId") Long factoryId);

    record OpenOrderRow(long id, long factoryId) {
    }

    /** 工厂仓的库存（自有）：加工厂、工厂仓、物料 SKU、数量；factoryId 是 null 时全部工厂 */
    @Select("<script>SELECT w.factory_id, w.id AS warehouse_id, s.material_sku_id AS sku_id, SUM(s.qty) AS qty "
            + "FROM inv_stock s JOIN bas_warehouse w ON w.id = s.warehouse_id "
            + "WHERE w.type = 'FACTORY' AND w.factory_id IS NOT NULL AND s.owner_type = 'OWN' "
            + "<if test='factoryId != null'>AND w.factory_id = #{factoryId}</if> "
            + "GROUP BY w.factory_id, w.id, s.material_sku_id</script>")
    List<FactoryStockRow> selectFactoryStock(@Param("factoryId") Long factoryId);

    record FactoryStockRow(long factoryId, long warehouseId, long skuId, BigDecimal qty) {
    }

    /** 加工厂的简称 */
    @Select("<script>SELECT id, short_name FROM sup_supplier WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<FactoryName> selectFactoryNames(@Param("ids") Collection<Long> factoryIds);

    record FactoryName(long id, String shortName) {
    }

    /** 加工厂的工厂仓 id：加工厂 → 仓库 */
    @Select("<script>SELECT factory_id, id FROM bas_warehouse WHERE type = 'FACTORY' AND factory_id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<FactoryWarehouse> selectFactoryWarehouses(@Param("ids") Collection<Long> factoryIds);

    record FactoryWarehouse(long factoryId, long id) {
    }
}
