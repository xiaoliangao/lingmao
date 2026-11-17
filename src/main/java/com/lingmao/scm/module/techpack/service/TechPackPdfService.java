package com.lingmao.scm.module.techpack.service;

/**
 * 资料包导出 PDF（PRD §6.4.6、架构 §11.1）：照甲方线下工艺单的版式，发给微信上的工厂。外发加工单、成品采购单的"打印"也调它（用单据锁定的版本）。
 * 看成本的权限由 Controller 管（price = true 没权限 403，不悄悄去掉价格）
 */
public interface TechPackPdfService {

    /** 导出来的文件：文件名（"27CF001-女式风衣-工艺单-V2.pdf"）和内容 */
    record PdfFile(String fileName, byte[] content) {
    }

    /**
     * 导出一个版本（草稿也能导，表头印"草稿"），每次写一条操作记录（export-pdf，备注写选了什么）。
     *
     * @param colorId  只出这个颜色的物料清单；null = 全部颜色。不是款式启用的颜色 → 1000
     * @param price    含参考单价、单件成本、核价和工序工价
     * @param appendix 附工艺标准文件
     */
    PdfFile export(long id, Long colorId, boolean price, boolean appendix);
}
