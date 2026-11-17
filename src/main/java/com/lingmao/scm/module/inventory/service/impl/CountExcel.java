package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.dto.CountRowVO;
import com.lingmao.scm.module.inventory.entity.InvCountItem;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 盘点单的 Excel（docs/api/04 §3.5.10 ~ §3.5.12）。盘点表就是导回实盘数的模板：导出时实盘数、备注填单上现有的，
 * 盘了一半导出、再导回不丢；导回只认 SKU 码、实盘数、备注三列，别的列认识但不读
 */
@Component
@RequiredArgsConstructor
class CountExcel {

    static final String SKU_CODE = "SKU 码";

    private final DictDataService dictDataService;

    @Data
    static class SheetRow {
        private String skuCode;
        private String materialCode;
        private String materialName;
        private String colorName;
        private String spec;
        private String unit;
        private BigDecimal bookQty;
        private BigDecimal countedQty;
        private String remark;
    }

    private static final ExcelSchema<SheetRow> SHEET = ExcelSchema.of("盘点表", SheetRow::new,
            ExcelColumn.text(SKU_CODE, SheetRow::getSkuCode, SheetRow::setSkuCode).required().code(),
            ExcelColumn.text("物料编码", SheetRow::getMaterialCode, SheetRow::setMaterialCode),
            ExcelColumn.text("名称", SheetRow::getMaterialName, SheetRow::setMaterialName),
            ExcelColumn.text("颜色", SheetRow::getColorName, SheetRow::setColorName),
            ExcelColumn.text("规格", SheetRow::getSpec, SheetRow::setSpec),
            ExcelColumn.text("单位", SheetRow::getUnit, SheetRow::setUnit),
            ExcelColumn.decimal("账面数", 3, SheetRow::getBookQty, SheetRow::setBookQty),
            ExcelColumn.decimal("实盘数", 3, SheetRow::getCountedQty, SheetRow::setCountedQty).nonNegative(),
            ExcelColumn.text("备注", SheetRow::getRemark, SheetRow::setRemark).maxLength(255));

    /** 导回用的：只读三列，账面数这些照样认识（不报"不认识的列"），但不读，填错了也不管 */
    private static final ExcelSchema<SheetRow> IMPORT = ExcelSchema.of("盘点表", SheetRow::new,
            ExcelColumn.text(SKU_CODE, SheetRow::getSkuCode, SheetRow::setSkuCode).required().code(),
            ExcelColumn.decimal("实盘数", 3, SheetRow::getCountedQty, SheetRow::setCountedQty).nonNegative(),
            ExcelColumn.text("备注", SheetRow::getRemark, SheetRow::setRemark).maxLength(255))
            .ignore("物料编码", "名称", "颜色", "规格", "单位", "账面数");

    byte[] sheet(List<InvCountItem> items, Map<Long, MaterialSkuBrief> briefs) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        List<SheetRow> rows = new ArrayList<>();
        for (InvCountItem i : items) {
            MaterialSkuBrief b = briefs.get(i.getMaterialSkuId());
            SheetRow r = new SheetRow();
            if (b != null) {
                r.setSkuCode(b.getSkuCode());
                r.setMaterialCode(b.getMaterialCode());
                r.setMaterialName(b.getMaterialName());
                r.setColorName(b.getColorName());
                r.setSpec(b.getSpec());
                r.setUnit(units.getOrDefault(b.getStockUnit(), b.getStockUnit()));
            }
            r.setBookQty(i.getBookQty());
            r.setCountedQty(i.getCountedQty());
            r.setRemark(i.getRemark());
            rows.add(r);
        }
        return Excel.export(SHEET, rows);
    }

    ExcelImport<SheetRow> read(MultipartFile file) {
        return Excel.read(IMPORT, file);
    }

    @Data
    static class ListRow {
        private String no;
        private String warehouse;
        private String purpose;
        private String scope;
        private LocalDate countDate;
        private String counter;
        private Integer itemCount;
        private Integer filledCount;
        private Integer gainCount;
        private Integer lossCount;
        private String status;
    }

    private static final ExcelSchema<ListRow> LIST = ExcelSchema.of("物料盘点单", ListRow::new,
            ExcelColumn.text("单号", ListRow::getNo, ListRow::setNo),
            ExcelColumn.text("仓库", ListRow::getWarehouse, ListRow::setWarehouse),
            ExcelColumn.text("用途", ListRow::getPurpose, ListRow::setPurpose),
            ExcelColumn.text("范围", ListRow::getScope, ListRow::setScope),
            ExcelColumn.date("盘点日期", ListRow::getCountDate, ListRow::setCountDate),
            ExcelColumn.text("盘点人", ListRow::getCounter, ListRow::setCounter),
            ExcelColumn.integer("物料种数", ListRow::getItemCount, ListRow::setItemCount),
            ExcelColumn.integer("已填", ListRow::getFilledCount, ListRow::setFilledCount),
            ExcelColumn.integer("盘盈", ListRow::getGainCount, ListRow::setGainCount),
            ExcelColumn.integer("盘亏", ListRow::getLossCount, ListRow::setLossCount),
            ExcelColumn.text("状态", ListRow::getStatus, ListRow::setStatus));

    byte[] export(List<CountRowVO> counts) {
        List<ListRow> rows = counts.stream().map(c -> {
            ListRow r = new ListRow();
            r.setNo(c.getNo());
            r.setWarehouse(c.getWarehouseName());
            r.setPurpose(c.getPurpose().getLabel());
            r.setScope(c.getScopeText());
            r.setCountDate(c.getCountDate());
            r.setCounter(c.getCounterName());
            r.setItemCount(c.getItemCount());
            r.setFilledCount(c.getFilledCount());
            r.setGainCount(c.getGainCount());
            r.setLossCount(c.getLossCount());
            r.setStatus(DocStatus.valueOf(c.getStatus()).getLabel());
            return r;
        }).toList();
        return Excel.export(LIST, rows);
    }
}
