package com.lingmao.scm.module.purchase.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.basic.service.MaterialService;
import com.lingmao.scm.module.purchase.dto.PurchaseRowVO;
import com.lingmao.scm.module.purchase.entity.PurchaseOrderItem;
import com.lingmao.scm.module.purchase.entity.PurchaseType;
import com.lingmao.scm.module.purchase.mapper.PurchaseOrderItemMapper;
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

/** 采购单导出（docs/api/03 §3.3.11）：一行一条明细，单头字段重复 */
@Component
@RequiredArgsConstructor
class PurchaseExcel {

    private final PurchaseOrderItemMapper itemMapper;
    private final MaterialService materialService;
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

    /** rows 的采购员、按钮已经由 Service 填好；明细这里一次取齐 */
    byte[] export(List<PurchaseRowVO> rows) {
        Map<Long, List<PurchaseOrderItem>> items = rows.isEmpty() ? Map.of()
                : itemMapper.selectByOrders(rows.stream().map(PurchaseRowVO::getId).toList()).stream()
                .collect(Collectors.groupingBy(PurchaseOrderItem::getPurchaseOrderId));
        Map<Long, MaterialSkuBrief> briefs = materialService.skuBriefs(items.values().stream().flatMap(List::stream)
                .map(PurchaseOrderItem::getMaterialSkuId).collect(Collectors.toSet()));
        Map<String, String> units = PurTexts.labels(dictDataService, "unit");
        List<ExportRow> out = new ArrayList<>();
        for (PurchaseRowVO po : rows) {
            for (PurchaseOrderItem i : items.getOrDefault(po.getId(), List.of())) {
                MaterialSkuBrief b = briefs.get(i.getMaterialSkuId());
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
                out.add(r);
            }
        }
        return Excel.export(EXPORT, out);
    }
}
