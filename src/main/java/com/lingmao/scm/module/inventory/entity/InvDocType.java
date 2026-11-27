package com.lingmao.scm.module.inventory.entity;

import com.lingmao.scm.module.biz.entity.BizType;
import lombok.Getter;

import java.util.Set;

/**
 * 入库单、出库单的类型（PRD §6.6.5，前端 types/inventory.ts 的 INV_DOC_TYPE，两边一起改）。
 * 一种类型在单据上长什么样：出仓、入仓能选哪种仓库（空集合 = 没有这一格），往来单位，源单，能不能手建，原因用哪个字典。
 * 过账时库存怎么动只看有没有出仓、入仓：有出仓的出仓减、有入仓的入仓加（移库两条都有），不按类型写 if。
 * TRADE 的客供入库 CUSTOMER_IN 等开关打开再加（数据库设计 §1.5）
 */
@Getter
public enum InvDocType {
    PURCHASE_IN("采购入库", InvDocKind.IN, Set.of(), Set.of("MATERIAL", "FACTORY"), Partner.SUPPLIER, BizType.PURCHASE_ORDER, true, null),
    RETURN_IN("退料入库", InvDocKind.IN, Set.of("FACTORY"), Set.of("MATERIAL"), Partner.FACTORY, BizType.PRODUCTION_ORDER, false, null),
    OTHER_IN("其他入库", InvDocKind.IN, Set.of(), Set.of("MATERIAL"), null, null, false, "stock_in_reason"),
    ISSUE_OUT("领料出库", InvDocKind.OUT, Set.of("MATERIAL"), Set.of("FACTORY"), Partner.FACTORY, BizType.PRODUCTION_ORDER, true, null),
    PURCHASE_RETURN("采购退货", InvDocKind.OUT, Set.of("MATERIAL", "FACTORY"), Set.of(), Partner.SUPPLIER, BizType.PURCHASE_ORDER, true, null),
    TRANSFER("调拨", InvDocKind.OUT, Set.of("MATERIAL", "FACTORY"), Set.of("MATERIAL", "FACTORY"), null, null, false, null),
    OTHER_OUT("其他出库", InvDocKind.OUT, Set.of("MATERIAL"), Set.of(), null, null, false, "stock_out_reason"),
    /** 耗料核销（RFC 015 §3）：做完货后从工厂仓扣掉这张生产单用掉的料，只能从生产单生成；一张单同时只有一张没作废的 */
    CONSUME_OUT("耗料核销", InvDocKind.OUT, Set.of("FACTORY"), Set.of(), Partner.FACTORY, BizType.PRODUCTION_ORDER, true, null);

    /** 往来单位：供应商（采购的两种）、加工厂（领料、退料，由源单或工厂仓带出） */
    public enum Partner { SUPPLIER, FACTORY }

    private final String label;
    private final InvDocKind kind;
    /** 出仓能选的仓库类型（bas_warehouse.type）；空 = 这种单没有出仓 */
    private final Set<String> from;
    private final Set<String> to;
    private final Partner partner;
    /** 源单的类型；null = 没有源单 */
    private final BizType source;
    /** 只能从源单生成（from-purchase、from-production），不能手建。退料入库能手建（不挂源单），挂了生产单的走 from-production */
    private final boolean sourceRequired;
    /** 原因用哪个字典（必填）；null = 原因是选填的一行字 */
    private final String reasonDict;

    InvDocType(String label, InvDocKind kind, Set<String> from, Set<String> to, Partner partner, BizType source,
               boolean sourceRequired, String reasonDict) {
        this.label = label;
        this.kind = kind;
        this.from = from;
        this.to = to;
        this.partner = partner;
        this.source = source;
        this.sourceRequired = sourceRequired;
        this.reasonDict = reasonDict;
    }

    public boolean hasFrom() {
        return !from.isEmpty();
    }

    public boolean hasTo() {
        return !to.isEmpty();
    }
}
