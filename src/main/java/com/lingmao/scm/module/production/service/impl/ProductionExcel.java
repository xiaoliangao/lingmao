package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.production.dto.ProductionRowVO;
import com.lingmao.scm.module.production.entity.ProductionKind;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.RUNNING;

/** 导出（docs/api/05 §3.2.7）：一张单一行，单价那列外发加工单叫"加工费"、成品采购单叫"成品单价" */
@Component
@RequiredArgsConstructor
class ProductionExcel {

    private final DictDataService dictDataService;

    byte[] export(ProductionKind kind, List<ProductionRowVO> orders) {
        Map<String, String> modes = labels("process_mode");
        List<ExportRow> rows = new ArrayList<>();
        for (ProductionRowVO o : orders) {
            ExportRow r = new ExportRow();
            r.setNo(o.getNo());
            r.setOrderNo(o.getOrderNo());
            r.setStyleCode(o.getStyleCode());
            r.setStyleName(o.getStyleName());
            r.setFactory(o.getFactoryShortName());
            r.setProcessMode(modes.getOrDefault(o.getProcessMode(), o.getProcessMode()));
            r.setQty(o.getTotalQty());
            r.setUnitPrice(o.getUnitPrice());
            r.setAmount(o.getTotalAmount());
            r.setDueDate(o.getDueDate());
            r.setExpectDate(o.getExpectDate());
            r.setReturnedQty(o.getReturnedQty());
            r.setDefectQty(o.getDefectQty());
            r.setMerch(o.getMerchName());
            DocStatus status = DocStatus.valueOf(o.getStatus());
            r.setStatus(status == RUNNING && o.getReturnedQty() - o.getDefectQty() > 0 ? "部分回货" : ProductionRules.STATUS.label(status).text());
            rows.add(r);
        }
        var columns = schema(kind);
        return Excel.export(com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.PRODUCTION)
                ? columns : columns.without(kind.getPriceLabel(), "金额"), rows);
    }

    @Data
    static class ExportRow {
        private String no;
        private String orderNo;
        private String styleCode;
        private String styleName;
        private String factory;
        private String processMode;
        private Integer qty;
        private BigDecimal unitPrice;
        private BigDecimal amount;
        private LocalDate dueDate;
        private LocalDate expectDate;
        private Integer returnedQty;
        private Integer defectQty;
        private String merch;
        private String status;
    }

    private static ExcelSchema<ExportRow> schema(ProductionKind kind) {
        return ExcelSchema.of(kind.getLabel(), ExportRow::new,
                ExcelColumn.text("单号", ExportRow::getNo, ExportRow::setNo),
                ExcelColumn.text("需求订单号", ExportRow::getOrderNo, ExportRow::setOrderNo),
                ExcelColumn.text("款号", ExportRow::getStyleCode, ExportRow::setStyleCode),
                ExcelColumn.text("款名", ExportRow::getStyleName, ExportRow::setStyleName),
                ExcelColumn.text("加工厂", ExportRow::getFactory, ExportRow::setFactory),
                ExcelColumn.text("加工方式", ExportRow::getProcessMode, ExportRow::setProcessMode),
                ExcelColumn.integer("数量", ExportRow::getQty, ExportRow::setQty),
                ExcelColumn.decimal(kind.getPriceLabel(), 4, ExportRow::getUnitPrice, ExportRow::setUnitPrice),
                ExcelColumn.decimal("金额", 2, ExportRow::getAmount, ExportRow::setAmount),
                ExcelColumn.date("交期", ExportRow::getDueDate, ExportRow::setDueDate),
                ExcelColumn.date("预计交期", ExportRow::getExpectDate, ExportRow::setExpectDate),
                ExcelColumn.integer("已回货", ExportRow::getReturnedQty, ExportRow::setReturnedQty),
                ExcelColumn.integer("次品", ExportRow::getDefectQty, ExportRow::setDefectQty),
                ExcelColumn.text("生产跟单", ExportRow::getMerch, ExportRow::setMerch),
                ExcelColumn.text("状态", ExportRow::getStatus, ExportRow::setStatus));
    }

    /** 字典 value → 中文（停用的也翻） */
    private Map<String, String> labels(String type) {
        return dictDataService.listByType(type).stream().collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }
}
