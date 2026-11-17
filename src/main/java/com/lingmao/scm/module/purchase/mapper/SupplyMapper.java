package com.lingmao.scm.module.purchase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 算可用量要的数（XML，架构 §5.4）：物料仓库存、采购行、已确认的算料单占着的。只读；库存、仓库只读 JOIN（架构 §3.2 规则 4）。
 * 数都交给 SupplyBook 算
 */
@Mapper
public interface SupplyMapper {

    record SkuQty(Long skuId, BigDecimal qty) {
    }

    /**
     * 一条采购行（SupplyBook.Line 的原料）。数量是采购单位
     *
     * @param eta 预计到货 ?? 行交期 ?? 单头交货日期
     */
    record LineRow(Long id, Long poId, String poNo, String purchaseType, String poStatus, Long skuId, Long warehouseId,
                   String warehouseType, BigDecimal qty, BigDecimal receivedQty, BigDecimal returnedQty, BigDecimal factor,
                   LocalDate eta, Long ownerItemId, Long ownerCalcId, String ownerCalcStatus) {
    }

    record UseRow(Long id, Long calcId, Long calcItemId, Long lineId, BigDecimal qty) {
    }

    record HeldRow(Long id, Long calcId, Long skuId, BigDecimal useStockQty, BigDecimal issuedQty) {
    }

    /** 物料仓的自有库存，按 SKU 合计 */
    List<SkuQty> selectMaterialStock(@Param("skuIds") Collection<Long> skuIds);

    /**
     * 这些 SKU 用得着的采购行（采购单已审核以后的）：没入齐的；已确认的算料单生成的、用着的；calcIds 这几张单生成的、用着的
     * （重新算料的上一张、详情里正在看的那张）
     */
    List<LineRow> selectLines(@Param("skuIds") Collection<Long> skuIds, @Param("calcIds") Collection<Long> calcIds);

    /** 已确认的算料单在这些 SKU 上的本次使用在途 */
    List<UseRow> selectActiveUses(@Param("skuIds") Collection<Long> skuIds);

    /** 已确认的算料单"我方采购"里这些 SKU 的行 */
    List<HeldRow> selectActiveItems(@Param("skuIds") Collection<Long> skuIds);

    /** 设了安全库存（> 0）、物料和 SKU 都启用的 SKU：补货提醒只看它们 */
    List<Long> selectSafetySkus();
}
