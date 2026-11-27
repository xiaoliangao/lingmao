package com.lingmao.scm.module.biz.entity;

import lombok.Getter;

import java.util.List;

/**
 * 全系统单据和主数据的类型（架构 §4.4）。操作记录、附件、变更提醒、多级审批都用"类型 + id"挂在一条记录上（doc_type、biz_type 列，存枚举名）；
 * 引用查询（ReferrerProvider）、价格档案的来源也按它认。加一种单据、主数据，就在这里加一项；和前端 types/biz.ts 的 BizType 一一对应。
 * 枚举名存进了库，建好以后不改名。
 * <p>
 * 放 biz 不放 common：它列的是具体的业务类型，common 只放不认识具体单据的东西（StatusFlow、DocStatus）。
 * 放 entity 包：别的域只能用 biz 的 service 接口、dto、entity（ArchitectureTest），它又是存进库里的值。
 * <p>
 * prefix 是单号、编码的固定前缀（PRD §5.2），交给 SerialNoGenerator：供应商 next(SUPPLIER.getPrefix(), 4) 得到 GYS0008，
 * 采购单 daily(PURCHASE_ORDER.getPrefix())。前缀不固定的是 null：物料编码的前缀是分类码，款号的前缀是大类（RFC 003 §7.1、RFC 005 §3），
 * SKU 码由物料编码拼出来；资料包、报工没有单号。
 * <p>
 * 一张表两种单据的（外发加工单和成品采购单、物料入库单和出库单，架构 §4.10）带几个 {@link Kind}：前缀和中文名按单据上的 kind 取，
 * 用 {@link #prefix(String)}、{@link #label(String)}；kind 的值和各域 kind 枚举的名字一样（OUTSOURCE / BUY、IN / OUT）
 */
@Getter
public enum BizType {
    SUPPLIER("供应商", "GYS"),
    MATERIAL("物料", null),
    MATERIAL_SKU("物料 SKU", null),
    STYLE("款式", null),
    TECH_PACK("资料包", null),
    ORDER("需求订单", "DD"),
    ORDER_CHANGE("订单变更单", "BG"),
    MRP_CALC("算料单", "SL"),
    PURCHASE_ORDER("采购单", "CG"),
    INV_DOC("物料出入库单", null, new Kind("IN", "物料入库单", "RK"), new Kind("OUT", "物料出库单", "CK")),
    INV_COUNT("物料盘点单", "PD"),
    PRODUCTION_ORDER("生产单", null, new Kind("OUTSOURCE", "外发加工单", "JG"), new Kind("BUY", "成品采购单", "CP")),
    PRODUCTION_RECEIPT("回货单", "HH"),
    PRODUCTION_ISSUE("生产异常", "YC"),
    /** 报工（照片挂在 biz_attachment 上），没有单号 */
    PRODUCTION_NODE_REPORT("报工", null),
    FG_DOC("成品出入库单", null, new Kind("IN", "成品入库单", "CR"), new Kind("OUT", "成品出库单", "CC")),
    /** 供应商对账单（RFC 019 §5）：引用了采购入库、采购退货、回货单 */
    STATEMENT("对账单", "DZ"),
    /** 质检记录（RFC 019 §6）：来料检验、产前样、中检、尾期验货，单号 ZJ… */
    QC_INSPECTION("质检记录", "ZJ");

    /** 一张表两种单据里的一种：kind 的值、中文名、单号前缀 */
    public record Kind(String code, String label, String prefix) {
    }

    private final String label;
    private final String prefix;
    private final List<Kind> kinds;

    BizType(String label, String prefix, Kind... kinds) {
        this.label = label;
        this.prefix = prefix;
        this.kinds = List.of(kinds);
    }

    /** 按 kind 取中文名；没有 kind 的类型传什么都返回 label（kind 传 null 就行） */
    public String label(String kind) {
        return kinds.isEmpty() ? label : kind(kind).label();
    }

    /** 按 kind 取单号前缀：daily(PRODUCTION_ORDER.prefix(kind))；没有 kind 的类型返回 prefix */
    public String prefix(String kind) {
        return kinds.isEmpty() ? prefix : kind(kind).prefix();
    }

    /** kind 不是这种单据的（拼错了、传了 null）是程序的错 */
    private Kind kind(String code) {
        return kinds.stream().filter(k -> k.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name() + " 没有 kind " + code + "，有的是 " + kinds));
    }
}
