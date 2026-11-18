package com.lingmao.scm.module.price.service;

import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.module.price.dto.CurrentPriceVO;
import com.lingmao.scm.module.price.dto.DocPrices;
import com.lingmao.scm.module.price.dto.MaterialPriceKey;
import com.lingmao.scm.module.price.dto.PriceLine;
import com.lingmao.scm.module.price.dto.PriceQuery;
import com.lingmao.scm.module.price.dto.PriceRef;
import com.lingmao.scm.module.price.dto.PriceRowVO;
import com.lingmao.scm.module.price.dto.PriceSaveReq;
import com.lingmao.scm.module.price.dto.PriceSource;
import com.lingmao.scm.module.price.dto.ProcessPriceLine;
import com.lingmao.scm.module.price.dto.ProcessPriceKey;
import com.lingmao.scm.module.price.entity.PriceType;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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
 * 单据审核时记价的 recordFromDoc 规则在 RFC 006 §5；第一张用它的是采购单（11-17），加工价等生产单开工时加
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

    /** 内部计算批量读取加工现价；口径同 currentProcess，取不到的 key 不在返回里。 */
    Map<ProcessPriceKey, PriceRef> currentProcesses(Collection<ProcessPriceKey> keys, LocalDate date);

    /**
     * 单据最后一级审核时记价（RFC 006 §5，在审核的事务里调）：逐行和单据日期的现价比，一样的跳过；
     * 现价是 SKU 单独定的记到 SKU；否则这张单里同一物料各行的价都一样就记物料层一条，不一样的各记到 SKU。
     * 同一组（供应商、物料、SKU、单价）只记一条；没有现价的原因写"第一次报价"。生效日期 = effectiveDate，来源 = source。
     * 不查录价权限：是单据审核带出来的
     */
    DocPrices recordFromDoc(PriceSource source, LocalDate effectiveDate, List<PriceLine> lines);

    /**
     * 生产单最后一级审核时记加工价（在审核的事务里调）：和单据日期的现价一样不记，返回 empty；不一样记一条（含税运口径按单据的），
     * 没有现价的原因写"第一次报价"，有现价又没写原因是程序的错（保存时漏了检查）。不查录价权限
     */
    Optional<PriceRef> recordProcessFromDoc(PriceSource source, LocalDate effectiveDate, ProcessPriceLine line);

    /** 单据反审核时作废它审核时记的价（docs/api/03 §3.3.6）：来源是这张单、没作废的都作废。不查录价权限。返回作废了几条 */
    int voidFromDoc(PriceSource source, String reason);
}
