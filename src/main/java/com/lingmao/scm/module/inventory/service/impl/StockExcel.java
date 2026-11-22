package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.dto.StockLogRowVO;
import com.lingmao.scm.module.inventory.dto.StockMovementRowVO;
import com.lingmao.scm.module.inventory.dto.StockRowVO;
import com.lingmao.scm.module.inventory.dto.StockSummaryRowVO;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/** 库存明细、按物料汇总、库存日志、进销存汇总的导出（docs/api/04 §3.6.5、§3.6.6） */
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
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private String currency;
        private String valuationStatus;
        private String reversalOfLogId;
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
            ExcelColumn.decimal("单价", 4, Row::getUnitPrice, Row::setUnitPrice),
            ExcelColumn.decimal("金额", 2, Row::getAmount, Row::setAmount),
            ExcelColumn.text("币种", Row::getCurrency, Row::setCurrency),
            ExcelColumn.text("估值状态", Row::getValuationStatus, Row::setValuationStatus),
            ExcelColumn.text("冲销原流水 ID", Row::getReversalOfLogId, Row::setReversalOfLogId).code(),
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

    /** 进销存一行：入库、出库拆开的列都导出（页面上默认藏着） */
    @Data
    static class MovementRow {
        private String skuCode;
        private String materialCode;
        private String materialName;
        private String colorName;
        private String spec;
        private String unit;
        private BigDecimal openingQty;
        private BigDecimal inQty;
        private BigDecimal outQty;
        private BigDecimal closingQty;
        private BigDecimal purchaseIn;
        private BigDecimal returnIn;
        private BigDecimal issueIn;
        private BigDecimal otherIn;
        private BigDecimal transferIn;
        private BigDecimal countGain;
        private BigDecimal openingIn;
        private BigDecimal issueOut;
        private BigDecimal returnOut;
        private BigDecimal purchaseReturn;
        private BigDecimal otherOut;
        private BigDecimal transferOut;
        private BigDecimal countLoss;
    }

    private static final ExcelSchema<MovementRow> MOVEMENT = ExcelSchema.of("进销存", MovementRow::new,
            ExcelColumn.text("SKU 码", MovementRow::getSkuCode, MovementRow::setSkuCode),
            ExcelColumn.text("物料编码", MovementRow::getMaterialCode, MovementRow::setMaterialCode),
            ExcelColumn.text("物料名称", MovementRow::getMaterialName, MovementRow::setMaterialName),
            ExcelColumn.text("颜色", MovementRow::getColorName, MovementRow::setColorName),
            ExcelColumn.text("规格", MovementRow::getSpec, MovementRow::setSpec),
            ExcelColumn.text("单位", MovementRow::getUnit, MovementRow::setUnit),
            ExcelColumn.decimal("期初", 3, MovementRow::getOpeningQty, MovementRow::setOpeningQty),
            ExcelColumn.decimal("入库", 3, MovementRow::getInQty, MovementRow::setInQty),
            ExcelColumn.decimal("出库", 3, MovementRow::getOutQty, MovementRow::setOutQty),
            ExcelColumn.decimal("期末", 3, MovementRow::getClosingQty, MovementRow::setClosingQty),
            ExcelColumn.decimal("采购入库", 3, MovementRow::getPurchaseIn, MovementRow::setPurchaseIn),
            ExcelColumn.decimal("退料入库", 3, MovementRow::getReturnIn, MovementRow::setReturnIn),
            ExcelColumn.decimal("领料入厂", 3, MovementRow::getIssueIn, MovementRow::setIssueIn),
            ExcelColumn.decimal("其他入库", 3, MovementRow::getOtherIn, MovementRow::setOtherIn),
            ExcelColumn.decimal("调入", 3, MovementRow::getTransferIn, MovementRow::setTransferIn),
            ExcelColumn.decimal("盘盈", 3, MovementRow::getCountGain, MovementRow::setCountGain),
            ExcelColumn.decimal("期初录入", 3, MovementRow::getOpeningIn, MovementRow::setOpeningIn),
            ExcelColumn.decimal("领料出库", 3, MovementRow::getIssueOut, MovementRow::setIssueOut),
            ExcelColumn.decimal("退料出厂", 3, MovementRow::getReturnOut, MovementRow::setReturnOut),
            ExcelColumn.decimal("采购退货", 3, MovementRow::getPurchaseReturn, MovementRow::setPurchaseReturn),
            ExcelColumn.decimal("其他出库", 3, MovementRow::getOtherOut, MovementRow::setOtherOut),
            ExcelColumn.decimal("调出", 3, MovementRow::getTransferOut, MovementRow::setTransferOut),
            ExcelColumn.decimal("盘亏", 3, MovementRow::getCountLoss, MovementRow::setCountLoss));

    byte[] movement(List<StockMovementRowVO> rows) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        return Excel.export(MOVEMENT, rows.stream().map(m -> {
            MovementRow r = new MovementRow();
            BeanUtils.copyProperties(m, r);
            r.setUnit(m.getStockUnit() == null ? null : units.getOrDefault(m.getStockUnit(), m.getStockUnit()));
            return r;
        }).toList());
    }

    /** 没有看价权限的：价值相关的四列都不出（走查 T-120e64：原来只去了单价、金额，币种、估值状态还在） */
    ExcelSchema<Row> logSchema(boolean financialColumns) {
        return financialColumns ? LOGS : LOGS.without("单价", "金额", "币种", "估值状态");
    }

    /** 估值状态写中文，和页面一样（docs/api/04 §3.6.4）：RECORDED 只表示留了原始价、额 */
    static String valuationLabel(String status) {
        if (status == null) return null;
        return switch (status) {
            case "RECORDED" -> "已记价";
            case "UNKNOWN" -> "估值未知";
            default -> status;
        };
    }

    List<Row> logRows(List<StockLogRowVO> logs, Map<String, String> units) {
        return logs.stream().map(l -> {
            Row r = row(l, units);
            r.setBizDate(l.getBizDate()); r.setChangeType(l.getChangeType().getLabel()); r.setDocNo(l.getDocNo());
            r.setWarehouse(l.getWarehouseName()); r.setDyeLot(l.getDyeLot()); r.setQty(l.getChangeQty());
            r.setBalanceQty(l.getBalanceQty()); r.setUnitPrice(l.getUnitPrice()); r.setAmount(l.getAmount());
            r.setCurrency(l.getCurrency()); r.setValuationStatus(valuationLabel(l.getValuationStatus()));
            r.setReversalOfLogId(l.getReversalOfLogId() == null ? null : l.getReversalOfLogId().toString());
            r.setOperator(l.getOperatorName()); r.setTime(l.getCreateTime() == null ? null : TIME.format(l.getCreateTime())); return r;
        }).toList();
    }

    byte[] logs(List<StockLogRowVO> logs) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        return Excel.export(logSchema(com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST)), logRows(logs, units));
    }
}
