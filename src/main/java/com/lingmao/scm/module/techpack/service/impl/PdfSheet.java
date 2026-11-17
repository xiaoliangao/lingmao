package com.lingmao.scm.module.techpack.service.impl;

import lombok.Value;

import java.util.List;

/**
 * 导出 PDF 的工艺单（PRD §6.4.6、架构 §11.1），模板 resources/pdf/techpack-sheet.html 只认它。
 * 选项在拼它的时候就套进去：不含价格时 price 是 false、cost 是 null、行上的单价成本是 null、工序工价不放；
 * 空的段、没填的尺寸指示不放。模板只管怎么摆，不再判断业务规则。
 * <p>
 * 用 Lombok 的 @Value（有 getXxx）不用 record：Thymeleaf 的表达式按 JavaBean 取属性
 */
@Value
class PdfSheet {

    /** "2026秋季工艺单" */
    String title;
    /** 不是已生效的版本，表头印的状态（"草稿""已作废"）；已生效是 null */
    String statusMark;
    Meta meta;
    /** 款式主图，data: 地址；没有是 null */
    String image;
    /** 没填尺寸指示是 null，整块不出 */
    Spec spec;
    /** "大身 220g 斜纹棉 2.35 米/件；…（M 码）"；没有面料、里料的行是 null */
    String fabricUsage;
    List<String> sizes;
    /** 一个颜色一张表 */
    List<BomTable> boms;
    boolean price;
    /** 含价格才有 */
    Cost cost;
    /** 车间缝制、后道、注意事项；一段都没写的那块不出 */
    List<CraftGroup> craftGroups;

    @Value
    static class Meta {
        String sampleNo;
        String styleCode;
        String styleName;
        String aliasName;
        String date;
        String packName;
        String colors;
        String sizes;
        String productName;
        String utilPct;
        String composition;
        String versionNote;
    }

    @Value
    static class Spec {
        String baseSize;
        List<SpecRow> rows;
    }

    @Value
    static class SpecRow {
        String point;
        String method;
        /** 按 sizes 的顺序，没填是 '' */
        List<String> values;
        String tolerance;
        String remark;
    }

    @Value
    static class BomTable {
        String colorName;
        String colorCode;
        List<BomRow> rows;
    }

    @Value
    static class BomRow {
        int seq;
        String part;
        /** 这个颜色配到的 SKU 编码，几个尺码配得不一样时用"、"连；没配是"未配料" */
        String skuCodes;
        /** "220g 斜纹棉-卡其 150cm"；没配料时只有物料名 */
        String materialName;
        String unit;
        /** 按 sizes 的顺序 */
        List<String> usages;
        String lossPct;
        String source;
        String remark;
        /** 含价格才有："18.50/米" */
        String price;
        /** 含价格才有：单件成本，或者"含在加工价里""客户出" */
        String cost;
    }

    @Value
    static class Cost {
        /** "按 卡其、M 码用量" */
        String basis;
        String fabric;
        String trim;
        String labor;
        String other;
        String total;
        /** "包装 2.00；吊牌 0.50"；没有是 null */
        String otherItems;
    }

    @Value
    static class CraftGroup {
        String name;
        List<CraftItem> items;
        /** 工序工价：含价格、在"车间缝制"那块、有工序才有，别的是空列表 */
        List<ProcessRow> processes;
        String stdSecondsTotal;
        String laborTotal;
    }

    @Value
    static class CraftItem {
        String label;
        String text;
        /** 包装要求那段的包装方式（"平铺"），别的段 null */
        String packingMode;
        /** data: 地址 */
        List<String> images;
        /** 放不进 PDF 的图（webp、文件不在了），一张一句 */
        List<String> skippedImages;
    }

    @Value
    static class ProcessRow {
        int seq;
        String category;
        String name;
        String requirement;
        String stdSeconds;
        String price;
        String remark;
    }
}
