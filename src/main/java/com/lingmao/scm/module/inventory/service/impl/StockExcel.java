package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** 库存明细、按物料汇总、库存日志的导出（docs/api/04 §3.6.5） */
@Component
@RequiredArgsConstructor
class StockExcel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<String, String> WAREHOUSE_TYPE = Map.of("MATERIAL", "物料仓", "FACTORY", "工厂仓");

    private final DictDataService dictDataService;

    /** 三张表共用的一行：用到哪些列由各自的列定义挑 */
    @Data
    static class Row {
        private String warehouse;
        private String warehouseType;
        private String skuCode;
        private String materialCode;
        private String materialName;
        private String colorName;
        private String spec;
        private String unit;
        private BigDecimal qty;
        private BigDecimal materialQty;
        private BigDecimal factoryQty;
        private BigDecimal safetyStock;
        private LocalDate bizDate;
        private String changeType;
        private String docNo;
        private String dyeLot;
        private BigDecimal balanceQty;
        private String operator;
        private String time;
    }

    private static final ExcelSchema<Row> STOCKS = ExcelSchema.of("库存", Row::new,
            ExcelColumn.text("仓库", Row::getWarehouse, Row::setWarehouse),
            ExcelColumn.text("仓库类型", Row::getWarehouseType, Row::setWarehouseType),
            ExcelColumn.text("SKU 码", Row::getSkuCode, Row::setSkuCode),
            ExcelColumn.text("物料编码", Row::getMaterialCode, Row::setMaterialCode),
            ExcelColumn.text("物料名称", Row::getMaterialName, Row::setMaterialName),
            ExcelColumn.text("颜色", Row::getColorName, Row::setColorName),
            ExcelColumn.text("规格", Row::getSpec, Row::setSpec),
            ExcelColumn.decimal("库存", 3, Row::getQty, Row::setQty),
            ExcelColumn.text("单位", Row::getUnit, Row::setUnit),
            ExcelColumn.decimal("最小库存", 3, Row::getSafetyStock, Row::setSafetyStock),
            ExcelColumn.text("最近变动", Row::getTime, Row::setTime));

    private static final ExcelSchema<Row> SUMMARY = ExcelSchema.of("库存汇总", Row::new,
            ExcelColumn.text("SKU 码", Row::getSkuCode, Row::setSkuCode),
            ExcelColumn.text("物料编码", Row::getMaterialCode, Row::setMaterialCode),
            ExcelColumn.text("物料名称", Row::getMaterialName, Row::setMaterialName),
            ExcelColumn.text("颜色", Row::getColorName, Row::setColorName),
            ExcelColumn.text("规格", Row::getSpec, Row::setSpec),
            ExcelColumn.decimal("物料仓", 3, Row::getMaterialQty, Row::setMaterialQty),
            ExcelColumn.decimal("工厂仓", 3, Row::getFactoryQty, Row::setFactoryQty),
            ExcelColumn.decimal("合计", 3, Row::getQty, Row::setQty),
            ExcelColumn.text("单位", Row::getUnit, Row::setUnit),
            ExcelColumn.decimal("最小库存", 3, Row::getSafetyStock, Row::setSafetyStock));

    private static final ExcelSchema<Row> LOGS = ExcelSchema.of("库存日志", Row::new,
            ExcelColumn.date("日期", Row::getBizDate, Row::setBizDate),
            ExcelColumn.text("类型", Row::getChangeType, Row::setChangeType),
            ExcelColumn.text("单号", Row::getDocNo, Row::setDocNo),
            ExcelColumn.text("仓库", Row::getWarehouse, Row::setWarehouse),
            ExcelColumn.text("SKU 码", Row::getSkuCode, Row::setSkuCode),
            ExcelColumn.text("物料名称", Row::getMaterialName, Row::setMaterialName),
            ExcelColumn.text("颜色", Row::getColorName, Row::setColorName),
            ExcelColumn.text("缸号", Row::getDyeLot, Row::setDyeLot),
            ExcelColumn.decimal("变动", 3, Row::getQty, Row::setQty),
            ExcelColumn.decimal("结存", 3, Row::getBalanceQty, Row::setBalanceQty),
            ExcelColumn.text("单位", Row::getUnit, Row::setUnit),
            ExcelColumn.text("操作人", Row::getOperator, Row::setOperator),
            ExcelColumn.text("时间", Row::getTime, Row::setTime));

    private Row row(MaterialSkuBrief b, Map<String, String> units) {
        Row r = new Row();
        r.setSkuCode(b.getSkuCode());
        r.setMaterialCode(b.getMaterialCode());
        r.setMaterialName(b.getMaterialName());
        r.setColorName(b.getColorName());
        r.setSpec(b.getSpec());
        r.setUnit(b.getStockUnit() == null ? null : units.getOrDefault(b.getStockUnit(), b.getStockUnit()));
        return r;
    }

    byte[] stocks(List<StockRowVO> stocks) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        return Excel.export(STOCKS, stocks.stream().map(s -> {
            Row r = row(s, units);
            r.setWarehouse(s.getWarehouseName());
            r.setWarehouseType(WAREHOUSE_TYPE.getOrDefault(s.getWarehouseType(), s.getWarehouseType()));
            r.setQty(s.getQty());
            r.setSafetyStock(s.getSafetyStock());
            r.setTime(s.getUpdateTime() == null ? null : TIME.format(s.getUpdateTime()));
            return r;
        }).toList());
    }

    byte[] summary(List<StockSummaryRowVO> rows) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        return Excel.export(SUMMARY, rows.stream().map(s -> {
            Row r = row(s, units);
            r.setMaterialQty(s.getMaterialQty());
            r.setFactoryQty(s.getFactoryQty());
            r.setQty(s.getTotalQty());
            r.setSafetyStock(s.getSafetyStock());
            return r;
        }).toList());
    }

    byte[] logs(List<StockLogRowVO> logs) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        return Excel.export(LOGS, logs.stream().map(l -> {
            Row r = row(l, units);
            r.setBizDate(l.getBizDate());
            r.setChangeType(l.getChangeType().getLabel());
            r.setDocNo(l.getDocNo());
            r.setWarehouse(l.getWarehouseName());
            r.setDyeLot(l.getDyeLot());
            r.setQty(l.getChangeQty());
            r.setBalanceQty(l.getBalanceQty());
            r.setOperator(l.getOperatorName());
            r.setTime(l.getCreateTime() == null ? null : TIME.format(l.getCreateTime()));
            return r;
        }).toList());
    }
}
