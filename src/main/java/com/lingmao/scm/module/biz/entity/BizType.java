package com.lingmao.scm.module.biz.entity;

import lombok.Getter;

/**
 * 全系统单据和主数据的类型（架构 §4.4）。操作记录、附件用"类型 + id"挂在一条记录上（biz_doc_log、biz_attachment 的 doc_type 列，存枚举名）；
 * 以后的引用查询（ReferrerProvider）、多级审批、价格档案的来源也按它认。加一种单据、主数据，就在这里加一项。
 * 枚举名存进了库，建好以后不改名。
 * <p>
 * 放 biz 不放 common：它列的是具体的业务类型，common 只放不认识具体单据的东西（StatusFlow、DocStatus）。
 * 放 entity 包：别的域只能用 biz 的 service 接口、dto、entity（ArchitectureTest），它又是存进库里的值。
 * <p>
 * prefix 是单号、编码的固定前缀（PRD §5.2），交给 SerialNoGenerator：供应商 next(SUPPLIER.getPrefix(), 4) 得到 GYS0008。
 * 前缀不固定的是 null：物料编码的前缀是分类码，款号的前缀是大类（RFC 003 §7.1、RFC 005 §3），SKU 码由物料编码拼出来。
 * 第 2 批先有这四个，单据随第 3 批加；一张表两种单据的（外发加工单和成品采购单、入库单和出库单），前缀按 kind 取（架构 §4.10）。
 */
@Getter
public enum BizType {
    SUPPLIER("供应商", "GYS"),
    MATERIAL("物料", null),
    MATERIAL_SKU("物料 SKU", null),
    STYLE("款式", null);

    private final String label;
    private final String prefix;

    BizType(String label, String prefix) {
        this.label = label;
        this.prefix = prefix;
    }
}
