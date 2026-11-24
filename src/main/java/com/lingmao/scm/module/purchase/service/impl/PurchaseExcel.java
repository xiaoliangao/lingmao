package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** 采购单导出（docs/api/03 §3.3.11）：一行一条明细，单头字段重复 */
@Component
@RequiredArgsConstructor
class PurchaseExcel {

    private final DictDataService dictDataService;

    @Data
    static class ExportRow {
        private String no;
        private String status;
        private String purchaseType;
        private String supplier;
        private LocalDate orderDate;
        private LocalDate deliveryDate;
        private String warehouse;
        private String buyer;
        private String company;
        private Integer seq;
        private String skuCode;
        private String materialName;
        private String color;
        private String spec;
        private String unit;
        private BigDecimal qty;
        private BigDecimal price;
        private BigDecimal amount;
        private String priceChangeReason;
        private LocalDate dueDate;
        private LocalDate expectDate;
        private BigDecimal receivedQty;
        private BigDecimal returnedQty;
        private BigDecimal pendingQty;
        private String followNote;
    }

    private static final ExcelSchema<ExportRow> EXPORT = ExcelSchema.of("采购单", ExportRow::new,
            ExcelColumn.text("采购单号", ExportRow::getNo, ExportRow::setNo),
            ExcelColumn.text("状态", ExportRow::getStatus, ExportRow::setStatus),
            ExcelColumn.text("采购类型", ExportRow::getPurchaseType, ExportRow::setPurchaseType),
            ExcelColumn.text("供应商", ExportRow::getSupplier, ExportRow::setSupplier),
            ExcelColumn.date("下单日期", ExportRow::getOrderDate, ExportRow::setOrderDate),
            ExcelColumn.date("交货日期", ExportRow::getDeliveryDate, ExportRow::setDeliveryDate),
            ExcelColumn.text("交货地点", ExportRow::getWarehouse, ExportRow::setWarehouse),
            ExcelColumn.text("采购员", ExportRow::getBuyer, ExportRow::setBuyer),
            ExcelColumn.text("主体", ExportRow::getCompany, ExportRow::setCompany),
            ExcelColumn.integer("行号", ExportRow::getSeq, ExportRow::setSeq),
            ExcelColumn.text("物料 SKU", ExportRow::getSkuCode, ExportRow::setSkuCode),
            ExcelColumn.text("物料名称", ExportRow::getMaterialName, ExportRow::setMaterialName),
            ExcelColumn.text("颜色", ExportRow::getColor, ExportRow::setColor),
            ExcelColumn.text("规格", ExportRow::getSpec, ExportRow::setSpec),
            ExcelColumn.text("采购单位", ExportRow::getUnit, ExportRow::setUnit),
            ExcelColumn.decimal("数量", 3, ExportRow::getQty, ExportRow::setQty),
            ExcelColumn.decimal("单价", 4, ExportRow::getPrice, ExportRow::setPrice),
            ExcelColumn.decimal("金额", 2, ExportRow::getAmount, ExportRow::setAmount),
            ExcelColumn.text("改价原因", ExportRow::getPriceChangeReason, ExportRow::setPriceChangeReason),
            ExcelColumn.date("交期", ExportRow::getDueDate, ExportRow::setDueDate),
            ExcelColumn.date("预计到货", ExportRow::getExpectDate, ExportRow::setExpectDate),
            ExcelColumn.decimal("已入库", 3, ExportRow::getReceivedQty, ExportRow::setReceivedQty),
            ExcelColumn.decimal("已退货", 3, ExportRow::getReturnedQty, ExportRow::setReturnedQty),
            ExcelColumn.decimal("未入库", 3, ExportRow::getPendingQty, ExportRow::setPendingQty),
            ExcelColumn.text("跟进备注", ExportRow::getFollowNote, ExportRow::setFollowNote));

    ExcelSchema<ExportRow> exportSchema() {
        return com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST)
                ? EXPORT : EXPORT.without("单价", "金额", "改价原因");
    }

    @FunctionalInterface
    interface ExportMapper {
        ExportRow row(PurchaseRowVO order, PurchaseOrderItem item, MaterialSkuBrief brief);
    }

    ExportMapper exportMapper() {
        Map<String, String> units = PurTexts.labels(dictDataService, "unit");
        return (po, i, b) -> {
            ExportRow r = new ExportRow();
            r.setNo(po.getNo());
            r.setStatus(PurchaseOrderServiceImpl.PO_STATUS.label(DocStatus.valueOf(po.getStatus())).text());
            r.setPurchaseType(PurchaseType.valueOf(po.getPurchaseType()).getLabel());
            r.setSupplier(po.getSupplierName());
            r.setOrderDate(po.getOrderDate());
            r.setDeliveryDate(po.getDeliveryDate());
            r.setWarehouse(po.getWarehouseName());
            r.setBuyer(po.getBuyerName());
            r.setCompany(po.getCompanyName());
            r.setSeq(i.getSeq());
            r.setSkuCode(b == null ? null : b.getSkuCode());
            r.setMaterialName(b == null ? null : b.getMaterialName());
            r.setColor(b == null ? null : b.getColorName());
            r.setSpec(b == null ? null : b.getSpec());
            r.setUnit(units.getOrDefault(i.getPurchaseUnit(), i.getPurchaseUnit()));
            r.setQty(i.getQty());
            r.setPrice(i.getPrice());
            r.setAmount(i.getAmount());
            r.setPriceChangeReason(i.getPriceChangeReason());
            r.setDueDate(i.getLineDueDate() != null ? i.getLineDueDate() : po.getDeliveryDate());
            r.setExpectDate(i.getExpectDate());
            r.setReceivedQty(i.getReceivedQty());
            r.setReturnedQty(i.getReturnedQty());
            r.setPendingQty(i.pending());
            r.setFollowNote(i.getFollowNote());
            return r;
        };
    }
}
