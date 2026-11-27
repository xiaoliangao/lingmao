package com.lingmao.scm.module.techpack.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 从 BOM 表导入的解析结果（POST /api/tech-packs/{id}/bom-import，前端 BomImport，docs/api/01 §3.12）：只解析、不写库，
 * 页面上看过、改过，点"加进物料清单"以后和手填的一样走保存草稿。
 *
 * @param sheets     文件里的每一张 sheet
 * @param sheet      用的是哪一张（Sheet.index）：自动对上的或者指定的
 * @param rows       这张 sheet 上的每一行物料，按表里的顺序
 * @param laborPrice 汇总块的加工费；鞋类 BOM 是几个"（加工）"块（冲裁、针车、成型）加起来。没写、没有看成本权限是 null
 * @param laborNote  加工费是几块合起来的时候，各块的数，如"冲裁及手工 2 + 针车 8 + 成型 10"，填进核价的加工费备注；别的是 null
 * @param fees       汇总块里的运费、鞋类 BOM 的包装这种费用块，带成核价的其他费用；没有看成本权限是空的
 */
public record BomImportVO(List<Sheet> sheets, Integer sheet, List<Row> rows, BigDecimal laborPrice, String laborNote, List<Fee> fees) {

    /** 一张 sheet：title 是第 1 行写的款号款名；bom = 认出了表头，能导 */
    public record Sheet(int index, String name, String title, boolean bom) {
    }

    /**
     * 一行。line 是 Excel 里的行号；item 和资料包详情里的物料行一个样子（没有 id），没认出物料是 null；
     * status：OK 认出来了，CHECK 能导但要看一下（notes 里写着），UNMATCHED 没认出物料，要先挑或者新建
     */
    public record Row(int line, Source source, BomItemVO item, String status, List<String> notes) {
    }

    /** 表里原样写的字（给人对照看）。price 没有看成本权限时不出 */
    public record Source(String section, String name, String spec, String color, String price, String unit, String usage,
                         String loss, String supplier, String remark) {
    }

    public record Fee(String name, BigDecimal amount) {
    }
}
