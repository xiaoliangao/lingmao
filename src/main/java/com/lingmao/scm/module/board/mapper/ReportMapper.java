package com.lingmao.scm.module.board.mapper;

import com.lingmao.scm.module.board.dto.OrderMarginRow;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 报表（RFC 019 §3，架构 §14.2：报表放 board，只读 SQL，慢了再上物化视图）。SQL 在 mapper/board/ReportMapper.xml。
 * 跨好几个域的表一起读，只读不写；品类线按 LineScope 筛（lines.limited 时只看自己的几条线）
 */
@Mapper
public interface ReportMapper {

    /** 采购行：交期在 from ~ to、单子审核过没作废；到齐日 = 入库累计够了的那天，lastDate 是最后一次入库 */
    List<PurchaseLineFact> selectPurchaseLines(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("lines") LineScope lines);

    /** @param need 要到的数（采购单位）= 数量 − 别的单超收冲抵过来的 */
    record PurchaseLineFact(long lineId, long supplierId, LocalDate dueDate, String status, BigDecimal need, LocalDate fullDate,
                            LocalDate lastDate) {
    }

    /** 生产单：交期在 from ~ to、审核过没作废；到齐日 = 良品累计够了的那天；goodByDue = 交期当天及之前回的良品 */
    List<FactoryOrderFact> selectFactoryOrders(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("lines") LineScope lines);

    record FactoryOrderFact(long prdOrderId, long factoryId, LocalDate dueDate, String status, int qty, LocalDate fullDate,
                            LocalDate lastDate, long goodByDue) {
    }

    /** 这段时间里登记的回货（没作废），按加工厂：件数（含次品）、次品 */
    List<DefectFact> selectDefects(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("lines") LineScope lines);

    record DefectFact(long factoryId, long qty, long defect) {
    }

    /** 在做的生产单，按品类线 × 款式品类：单数、还没回的件数、其中过了交期的 */
    List<WipFact> selectWip(@Param("today") LocalDate today, @Param("lines") LineScope lines);

    record WipFact(String productLine, Long categoryId, int orderCount, long wipQty, long lateQty) {
    }

    /** 下单日期在 from ~ to 的生产单（没作废）件数，按品类线 × 款式品类 */
    List<LineQtyFact> selectNewQty(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("lines") LineScope lines);

    /** 回货日期在 from ~ to 的良品，按品类线 × 款式品类 */
    List<LineQtyFact> selectGoodQty(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("lines") LineScope lines);

    record LineQtyFact(String productLine, Long categoryId, long qty) {
    }

    /**
     * 物料仓里有库存、最后一次出入库（盘点、反审核冲掉的不算）在 before 当天或之前、或者从没出入过的；
     * unitPrice 是这个 SKU 最近一次没被冲掉的入库单价，withPrice = false 时不查（只要条数）
     */
    List<StaleFact> selectStale(@Param("before") LocalDate before, @Param("warehouseId") Long warehouseId, @Param("withPrice") boolean withPrice);

    record StaleFact(long warehouseId, String warehouseName, long skuId, BigDecimal qty, LocalDate lastDate, BigDecimal unitPrice) {
    }

    /**
     * 已审核以后的需求订单，下单日期在 from ~ to；最多 limit 条（超了页面提示缩小范围）。
     * keyword 是 Keywords.contains 包好的（%…%，转义过），查订单号、客户订单号、品牌。
     * 逐单列销售额、毛利，所以和订单列表一样按数据范围（本人 = 建的、销售、跟单是自己）筛，品类线在 lines 里
     */
    List<OrderMarginRow> selectOrders(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("keyword") String keyword,
                                      @Param("productLine") String productLine, @Param("lines") LineScope lines,
                                      @Param("scope") DataScope scope, @Param("limit") int limit);

    /** 这些订单每版资料包多少件（没锁资料包的 techPackId 是 null） */
    List<PackQty> selectOrderPackQty(@Param("ids") Collection<Long> orderIds);

    record PackQty(long orderId, Long techPackId, long qty) {
    }
}
