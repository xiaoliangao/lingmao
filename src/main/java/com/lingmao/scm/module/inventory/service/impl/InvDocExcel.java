package com.lingmao.scm.module.inventory.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.basic.dto.MaterialSkuBrief;
import com.lingmao.scm.module.inventory.dto.InvDocRowVO;
import com.lingmao.scm.module.inventory.entity.InvDocItem;
import com.lingmao.scm.module.inventory.entity.InvDocKind;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 入库单、出库单导出（docs/api/04 §3.2.8）：一行一条明细，单头字段重复 */
@Component
@RequiredArgsConstructor
class InvDocExcel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    private final DictDataService dictDataService;

    @Data
    static class Row {
        private String no;
        private String docType;
        private String status;
        private LocalDate docDate;
        private String fromWarehouse;
        private String toWarehouse;
        private String partner;
        private String sourceNo;
        private String reason;
        private Integer seq;
        private String skuCode;
        private String materialCode;
        private String materialName;
        private String colorName;
        private String spec;
        private BigDecimal qty;
        private String unit;
        private BigDecimal purchaseQty;
        private String purchaseUnit;
        private BigDecimal price;
        private BigDecimal amount;
        private String dyeLot;
        private Integer rolls;
        private String itemRemark;
        private String createBy;
        private String createTime;
    }

    private static ExcelSchema<Row> schema(InvDocKind kind) {
        return ExcelSchema.of(kind == InvDocKind.IN ? "物料入库单" : "物料出库单", Row::new,
                ExcelColumn.text("单号", Row::getNo, Row::setNo),
                ExcelColumn.text("类型", Row::getDocType, Row::setDocType),
                ExcelColumn.text("状态", Row::getStatus, Row::setStatus),
                ExcelColumn.date("日期", Row::getDocDate, Row::setDocDate),
                ExcelColumn.text("出仓", Row::getFromWarehouse, Row::setFromWarehouse),
                ExcelColumn.text("入仓", Row::getToWarehouse, Row::setToWarehouse),
                ExcelColumn.text("往来单位", Row::getPartner, Row::setPartner),
                ExcelColumn.text("源单号", Row::getSourceNo, Row::setSourceNo),
                ExcelColumn.text("原因", Row::getReason, Row::setReason),
                ExcelColumn.integer("行", Row::getSeq, Row::setSeq),
                ExcelColumn.text("SKU 码", Row::getSkuCode, Row::setSkuCode),
                ExcelColumn.text("物料编码", Row::getMaterialCode, Row::setMaterialCode),
                ExcelColumn.text("物料名称", Row::getMaterialName, Row::setMaterialName),
                ExcelColumn.text("颜色", Row::getColorName, Row::setColorName),
                ExcelColumn.text("规格", Row::getSpec, Row::setSpec),
                ExcelColumn.decimal("数量", 3, Row::getQty, Row::setQty),
                ExcelColumn.text("单位", Row::getUnit, Row::setUnit),
                ExcelColumn.decimal("采购单位数量", 3, Row::getPurchaseQty, Row::setPurchaseQty),
                ExcelColumn.text("采购单位", Row::getPurchaseUnit, Row::setPurchaseUnit),
                ExcelColumn.decimal("单价", 4, Row::getPrice, Row::setPrice),
                ExcelColumn.decimal("金额", 2, Row::getAmount, Row::setAmount),
                ExcelColumn.text("缸号", Row::getDyeLot, Row::setDyeLot),
                ExcelColumn.integer("卷数", Row::getRolls, Row::setRolls),
                ExcelColumn.text("备注", Row::getItemRemark, Row::setItemRemark),
                ExcelColumn.text("制单人", Row::getCreateBy, Row::setCreateBy),
                ExcelColumn.text("制单时间", Row::getCreateTime, Row::setCreateTime));
    }

    byte[] export(InvDocKind kind, List<InvDocRowVO> docs, Map<Long, List<InvDocItem>> items,
                  Map<Long, MaterialSkuBrief> briefs, Map<Long, String> userNames) {
        Map<String, String> units = InvTexts.labels(dictDataService, "unit");
        Map<String, String> inReasons = InvTexts.labels(dictDataService, "stock_in_reason");
        Map<String, String> outReasons = InvTexts.labels(dictDataService, "stock_out_reason");
        List<Row> rows = new ArrayList<>();
        for (InvDocRowVO d : docs) {
            for (InvDocItem i : items.getOrDefault(d.getId(), List.of())) {
                MaterialSkuBrief b = briefs.get(i.getMaterialSkuId());
                Row r = new Row();
                r.setNo(d.getNo());
                r.setDocType(d.getDocType().getLabel());
                r.setStatus(DocStatus.valueOf(d.getStatus()).getLabel());
                r.setDocDate(d.getDocDate());
                r.setFromWarehouse(d.getFromWarehouseName());
                r.setToWarehouse(d.getToWarehouseName());
                r.setPartner(d.getPartnerName());
                r.setSourceNo(d.getSourceNo());
                Map<String, String> reasons = d.getDocType().getReasonDict() == null ? Map.of()
                        : kind == InvDocKind.IN ? inReasons : outReasons;
                r.setReason(d.getReason() == null ? null : reasons.getOrDefault(d.getReason(), d.getReason()));
                r.setSeq(i.getSeq());
                if (b != null) {
                    r.setSkuCode(b.getSkuCode());
                    r.setMaterialCode(b.getMaterialCode());
                    r.setMaterialName(b.getMaterialName());
                    r.setColorName(b.getColorName());
                    r.setSpec(b.getSpec());
                    r.setUnit(units.getOrDefault(b.getStockUnit(), b.getStockUnit()));
                }
                r.setQty(i.getQty());
                r.setPurchaseQty(i.getPurchaseQty());
                r.setPurchaseUnit(i.getPurchaseUnit() == null ? null : units.getOrDefault(i.getPurchaseUnit(), i.getPurchaseUnit()));
                r.setPrice(i.getPrice());
                r.setAmount(i.getAmount());
                r.setDyeLot(i.getDyeLot());
                r.setRolls(i.getRolls());
                r.setItemRemark(i.getRemark());
                r.setCreateBy(d.getCreateBy() == null ? null : userNames.get(d.getCreateBy()));
                r.setCreateTime(d.getCreateTime() == null ? null : TIME.format(d.getCreateTime()));
                rows.add(r);
            }
        }
        var columns = schema(kind);
        return Excel.export(com.lingmao.scm.security.FieldPermissions.has(com.lingmao.scm.security.FieldPermissions.COST)
                ? columns : columns.without("单价", "金额"), rows);
    }
}
