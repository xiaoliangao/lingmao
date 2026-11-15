package com.lingmao.scm.module.price.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.price.dto.CurrentPriceVO;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.entity.PriceType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 价格档案（RFC 006，PRD §6.10）：物料价、加工价只插不改，调价加一条、录错了作废；按日期取现价。
 * <p>
 * 前四个方法给页面（物料、供应商、款式详情的"价格记录"页签和录价对话框）；
 * 后三个 current* 给第 3、4 批：资料包的参考单价、排单的加工费、采购单的默认单价都调它们（RFC 006 §4）。
 * 现价的口径：没作废、生效日期不晚于 date 的最新一条（同一天取后录的）；物料价先找这个 SKU 的、没有再找物料层的，
 * 而且只认物料<b>现在的</b>采购单位的记录（物料改过采购单位，旧单位的价不再当现价）。
 * 取不到返回空，调用方退回到物料 SKU 单价、物料参考单价（PRD §6.10 规则 3）；生产单、核价手填。
 * <p>
 * 单据审核时记价的 recordFromDoc 签名在 RFC 006 §4、规则在 §5，第一张审核时记价的单据开工时加到这里（不提前写没人调的代码）
 */
public interface PriceService {

    /** 列表：全部历史，或者"只看现价"每组一行（PriceQuery 的注释）。要 price:price:list */
    PageResult<PriceRowVO> page(PriceQuery query);

    /**
     * 录价对话框用：这一组那一天的现价（口径同上）、这条价的单位、供应商的默认税率。
     * 物料、款式还没选时只给供应商的税率
     */
    CurrentPriceVO currentFor(PriceType type, long supplierId, Long materialId, Long materialSkuId, Long styleId,
                              String processMode, LocalDate date);

    /**
     * 录一条（RFC 006 §3）：按类型要 price:material:edit 或 price:process:edit，不符 403。
     * 供应商要能打样（待准入、审批中、合作中）；停用的物料、SKU 不能录，SKU 要是这个物料的；
     * 和同一层级的现价一样 → 1000"不用再录"，不一样 → 调价原因必填，没有现价（第一次报价、第一次给这个 SKU 单独定价）原因可以不填。
     * 返回新的 id
     */
    long record(PriceSaveReq req);

    /** 作废（原因必填）：按那条的类型要改价权限；已经作废了 → 1002。作废后取价自动退到上一条 */
    void voidPrice(long id, String reason);

    /**
     * 物料现价：先找这个 SKU 的，没有再找物料层的（materialSkuId 为空就只找物料层的）。
     * 只认 unit 等于物料现在采购单位的记录。没有返回 empty。物料不存在 404
     */
    Optional<PriceRef> currentMaterial(long supplierId, long materialId, Long materialSkuId, LocalDate date);

    /**
     * 批量版：采购单、资料包一次带出几十行，一条 SQL（DISTINCT ON）。口径和 currentMaterial 一样，取不到的 key 不在返回里。
     * key 带供应商：资料包每行的默认供应商不一样。有不存在的物料 404
     */
    Map<MaterialPriceKey, PriceRef> currentMaterials(Collection<MaterialPriceKey> keys, LocalDate date);

    /** 加工价现价：工厂 × 款式 × 加工方式。核价按成品采购算时 processMode = FULL（包工包料）。没有返回 empty */
    Optional<PriceRef> currentProcess(long supplierId, long styleId, String processMode, LocalDate date);
}
