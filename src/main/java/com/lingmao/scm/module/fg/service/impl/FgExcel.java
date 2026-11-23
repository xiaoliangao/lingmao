package com.lingmao.scm.module.fg.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.fg.dto.FgDocRowVO;
import com.lingmao.scm.module.fg.dto.FgStockByStyleRowVO;
import com.lingmao.scm.module.fg.dto.FgStockLogRowVO;
import com.lingmao.scm.module.fg.dto.FgStockRowVO;
import com.lingmao.scm.module.fg.dto.FgStyleOptionVO;
import com.lingmao.scm.module.fg.entity.FgDocItem;
import com.lingmao.scm.module.fg.entity.FgDocKind;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.security.FieldPermissions;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 成品单据、库存、库存日志的导出，其他入库的明细导入（docs/api/07 §3.2.8、§3.6） */
@Component
@RequiredArgsConstructor
class FgExcel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    static final String SKU_CODE = "SKU 码";
    static final long MAX_MATRIX_COLUMNS = 16_384;
    static final long MAX_MATRIX_CELLS = 5_000_000;

    /** 同步矩阵固定预算，包含四个固定列和表头；构造列定义/宽表前校验。 */
    static void checkMatrixBudget(long rows, long sizes) {
        if (rows < 0 || sizes < 0) throw new IllegalArgumentException("矩阵维度不能为负数");
        if (rows > Excel.MAX_EXPORT_ROWS) throw new BizException("成品库存颜色超过 50000 行，请缩小范围");
        if (sizes > MAX_MATRIX_COLUMNS - 4)
            throw new BizException("成品库存矩阵超过 16384 列（含四个固定列），请缩小尺码范围");
        long cells = (rows + 1) * (sizes + 4);
        if (cells > MAX_MATRIX_CELLS)
            throw new BizException("成品库存矩阵超过 5000000 个单元格（含表头），请缩小导出范围");
    }

    private final DictDataService dictDataService;

    // ---------- 单据导出：一行一条明细，单头字段重复 ----------

    @Data
    static class DocRow {
        private String no;
        private String docType;
        private String status;
        private LocalDate docDate;
        private String warehouse;
        private String sourceNo;
        private String factory;
        private String reason;
        private String shipTo;
        private String logisticsNo;
        private Integer seq;
        private String skuCode;
        private String styleCode;
        private String styleName;
        private String colorName;
        private String sizeCode;
        private Integer qty;
        private BigDecimal price;
        private BigDecimal amount;
        private String itemRemark;
        private String createBy;
        private String createTime;
    }

    private static ExcelSchema<DocRow> docSchema(FgDocKind kind) {
        boolean in = kind == FgDocKind.IN;
        List<ExcelColumn<DocRow, ?>> cols = new ArrayList<>(List.of(
                ExcelColumn.text("单号", DocRow::getNo, DocRow::setNo),
                ExcelColumn.text("类型", DocRow::getDocType, DocRow::setDocType),
                ExcelColumn.text("状态", DocRow::getStatus, DocRow::setStatus),
                ExcelColumn.date("日期", DocRow::getDocDate, DocRow::setDocDate),
                ExcelColumn.text(in ? "入仓" : "出仓", DocRow::getWarehouse, DocRow::setWarehouse),
                ExcelColumn.text(in ? "回货单号" : "订单号", DocRow::getSourceNo, DocRow::setSourceNo)));
        if (in) {
            cols.add(ExcelColumn.text("加工厂", DocRow::getFactory, DocRow::setFactory));
        }
        cols.add(ExcelColumn.text("原因", DocRow::getReason, DocRow::setReason));
        if (!in) {
            cols.add(ExcelColumn.text("收货方", DocRow::getShipTo, DocRow::setShipTo));
            cols.add(ExcelColumn.text("物流单号", DocRow::getLogisticsNo, DocRow::setLogisticsNo));
        }
        cols.addAll(List.of(
                ExcelColumn.integer("行", DocRow::getSeq, DocRow::setSeq),
                ExcelColumn.text(SKU_CODE, DocRow::getSkuCode, DocRow::setSkuCode),
                ExcelColumn.text("款号", DocRow::getStyleCode, DocRow::setStyleCode),
                ExcelColumn.text("款名", DocRow::getStyleName, DocRow::setStyleName),
                ExcelColumn.text("颜色", DocRow::getColorName, DocRow::setColorName),
                ExcelColumn.text("尺码", DocRow::getSizeCode, DocRow::setSizeCode),
                ExcelColumn.integer("件数", DocRow::getQty, DocRow::setQty)));
        if (!in) {
            cols.add(ExcelColumn.decimal("单价", 4, DocRow::getPrice, DocRow::setPrice));
            cols.add(ExcelColumn.decimal("金额", 2, DocRow::getAmount, DocRow::setAmount));
        }
        cols.addAll(List.of(
                ExcelColumn.text("备注", DocRow::getItemRemark, DocRow::setItemRemark),
                ExcelColumn.text("制单人", DocRow::getCreateBy, DocRow::setCreateBy),
                ExcelColumn.text("制单时间", DocRow::getCreateTime, DocRow::setCreateTime)));
        @SuppressWarnings("unchecked")
        ExcelColumn<DocRow, ?>[] array = cols.toArray(ExcelColumn[]::new);
        return ExcelSchema.of(in ? "成品入库单" : "成品出库单", DocRow::new, array);
    }

    byte[] exportDocs(FgDocKind kind, List<FgDocRowVO> docs, Map<Long, List<FgDocItem>> items,
                      Map<Long, StyleOrderInfo> bySku, Map<Long, String> userNames) {
        Map<String, String> reasons = kind == FgDocKind.IN ? dictDataService.labels("fg_in_reason") : dictDataService.labels("fg_out_reason");
        List<DocRow> rows = new ArrayList<>();
        for (FgDocRowVO d : docs) {
            for (FgDocItem i : items.getOrDefault(d.getId(), List.of())) {
                StyleOrderInfo style = bySku.get(i.getStyleSkuId());
                StyleOrderInfo.Sku sku = FgStyles.sku(style, i.getStyleSkuId());
                DocRow r = new DocRow();
                r.setNo(d.getNo());
                r.setDocType(d.getDocType().getLabel());
                r.setStatus(DocStatus.valueOf(d.getStatus()).getLabel());
                r.setDocDate(d.getDocDate());
                r.setWarehouse(d.getWarehouseName());
                r.setSourceNo(kind == FgDocKind.IN ? d.getReceiptNo() : d.getOrderNo());
                r.setFactory(d.getFactoryName());
                r.setReason(d.getReason() == null ? null : reasons.getOrDefault(d.getReason(), d.getReason()));
                r.setShipTo(d.getShipTo());
                r.setLogisticsNo(d.getLogisticsNo());
                r.setSeq(i.getSeq());
                r.setSkuCode(FgStyles.code(style, i.getStyleSkuId()));
                if (style != null) {
                    r.setStyleCode(style.code());
                    r.setStyleName(style.name());
                    r.setColorName(FgStyles.colorName(style, sku));
                }
                r.setSizeCode(sku == null ? null : sku.sizeCode());
                r.setQty(i.getQty());
                r.setPrice(i.getPrice());
                r.setAmount(i.getAmount());
                r.setItemRemark(i.getRemark());
                r.setCreateBy(d.getCreateBy() == null ? null : userNames.get(d.getCreateBy()));
                r.setCreateTime(d.getCreateTime() == null ? null : TIME.format(d.getCreateTime()));
                rows.add(r);
            }
        }
        ExcelSchema<DocRow> schema = docSchema(kind);
        // 发货单价、金额是订单的（docs/api/07 §1：没有 order:price:query 的人导出里没有这两列）
        return Excel.export(kind == FgDocKind.IN || FieldPermissions.has(FieldPermissions.ORDER) ? schema : schema.without("单价", "金额"), rows);
    }

    // ---------- 其他入库导入明细 ----------

    @Data
    static class ItemRow {
        private String skuCode;
        private Integer qty;
        private String remark;
    }

    private static final ExcelSchema<ItemRow> ITEMS = ExcelSchema.of("成品入库明细", ItemRow::new,
            ExcelColumn.text(SKU_CODE, ItemRow::getSkuCode, ItemRow::setSkuCode).required().code()
                    .example("A2611001-BK-M").note("款式库里 SKU 一页的 SKU 码；停用的也能填"),
            ExcelColumn.integer("件数", ItemRow::getQty, ItemRow::setQty).required()
                    .check(q -> q < 1 ? "要大于 0" : q > 99_999_999 ? "不能超过 99,999,999" : null),
            ExcelColumn.text("备注", ItemRow::getRemark, ItemRow::setRemark).maxLength(255))
            .ignore("款号", "款名", "颜色", "尺码");

    byte[] itemsTemplate() {
        return Excel.template(ITEMS);
    }

    ExcelImport<ItemRow> readItems(MultipartFile file) {
        return Excel.read(ITEMS, file);
    }

    static String code(String s) {
        return s == null || s.isBlank() ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    // ---------- 库存、库存日志导出 ----------

    @Data
    static class StockRow {
        private String warehouse;
        private String skuCode;
        private String styleCode;
        private String styleName;
        private String colorName;
        private String sizeCode;
        private Integer qty;
    }

    private static final ExcelSchema<StockRow> STOCK = ExcelSchema.of("成品库存", StockRow::new,
            ExcelColumn.text("仓库", StockRow::getWarehouse, StockRow::setWarehouse),
            ExcelColumn.text(SKU_CODE, StockRow::getSkuCode, StockRow::setSkuCode),
            ExcelColumn.text("款号", StockRow::getStyleCode, StockRow::setStyleCode),
            ExcelColumn.text("款名", StockRow::getStyleName, StockRow::setStyleName),
            ExcelColumn.text("颜色", StockRow::getColorName, StockRow::setColorName),
            ExcelColumn.text("尺码", StockRow::getSizeCode, StockRow::setSizeCode),
            ExcelColumn.integer("库存", StockRow::getQty, StockRow::setQty));

    byte[] exportStock(List<FgStockRowVO> list) {
        return Excel.export(STOCK, list.stream().map(s -> {
            StockRow r = new StockRow();
            r.setWarehouse(s.getWarehouseName());
            r.setSkuCode(s.getSkuCode());
            r.setStyleCode(s.getStyleCode());
            r.setStyleName(s.getStyleName());
            r.setColorName(s.getColorName());
            r.setSizeCode(s.getSizeCode());
            r.setQty(s.getQty());
            return r;
        }).toList());
    }

    @Data
    static class ByStyleRow {
        private String styleCode;
        private String styleName;
        private String colorName;
        private Map<String, BigDecimal> qtys = new HashMap<>();
        private BigDecimal total;
    }

    /** 按款：一款一个颜色一行，尺码一列一列往右（各款尺码组不同，列取所有款尺码的并集，按出现的顺序），没有这个尺码的格子空着 */
    byte[] exportByStyle(List<FgStockByStyleRowVO> list) {
        long outputRows = list.stream().mapToLong(s -> s.colors().size()).sum();
        List<String> sizes = list.stream().flatMap(s -> s.sizes().stream()).distinct().toList();
        checkMatrixBudget(outputRows, sizes.size());
        List<ExcelColumn<ByStyleRow, ?>> cols = new ArrayList<>(List.of(
                ExcelColumn.text("款号", ByStyleRow::getStyleCode, ByStyleRow::setStyleCode),
                ExcelColumn.text("款名", ByStyleRow::getStyleName, ByStyleRow::setStyleName),
                ExcelColumn.text("颜色", ByStyleRow::getColorName, ByStyleRow::setColorName)));
        for (String size : sizes) {
            cols.add(ExcelColumn.decimal(size, 0, r -> r.getQtys().get(size), (r, v) -> r.getQtys().put(size, v)));
        }
        cols.add(ExcelColumn.decimal("合计", 0, ByStyleRow::getTotal, ByStyleRow::setTotal));
        List<ByStyleRow> rows = new ArrayList<>();
        for (FgStockByStyleRowVO s : list) {
            Map<Long, List<FgStyleOptionVO.Sku>> byColor = s.skus().stream()
                    .collect(java.util.stream.Collectors.groupingBy(FgStyleOptionVO.Sku::colorId));
            for (FgStyleOptionVO.Color c : s.colors()) {
                ByStyleRow r = new ByStyleRow();
                r.setStyleCode(s.code());
                r.setStyleName(s.name());
                r.setColorName(c.name());
                long total = 0;
                for (FgStyleOptionVO.Sku k : byColor.getOrDefault(c.id(), List.of())) {
                    Long q = s.qtys().get(String.valueOf(k.id()));
                    if (q != null) {
                        r.getQtys().put(k.sizeCode(), BigDecimal.valueOf(q));
                        total += q;
                    }
                }
                r.setTotal(BigDecimal.valueOf(total));
                rows.add(r);
            }
        }
        @SuppressWarnings("unchecked")
        ExcelColumn<ByStyleRow, ?>[] array = cols.toArray(ExcelColumn[]::new);
        return Excel.export(ExcelSchema.of("成品库存（按款）", ByStyleRow::new, array), rows);
    }

    @Data
    static class LogRow {
        private LocalDate bizDate;
        private String changeType;
        private String docNo;
        private String warehouse;
        private String skuCode;
        private String styleCode;
        private String colorName;
        private String sizeCode;
        private Integer changeQty;
        private Integer balanceQty;
        private String createBy;
        private String createTime;
    }

    private static final ExcelSchema<LogRow> LOG = ExcelSchema.of("成品库存日志", LogRow::new,
            ExcelColumn.date("日期", LogRow::getBizDate, LogRow::setBizDate),
            ExcelColumn.text("类型", LogRow::getChangeType, LogRow::setChangeType),
            ExcelColumn.text("单号", LogRow::getDocNo, LogRow::setDocNo),
            ExcelColumn.text("仓库", LogRow::getWarehouse, LogRow::setWarehouse),
            ExcelColumn.text(SKU_CODE, LogRow::getSkuCode, LogRow::setSkuCode),
            ExcelColumn.text("款号", LogRow::getStyleCode, LogRow::setStyleCode),
            ExcelColumn.text("颜色", LogRow::getColorName, LogRow::setColorName),
            ExcelColumn.text("尺码", LogRow::getSizeCode, LogRow::setSizeCode),
            ExcelColumn.integer("变动", LogRow::getChangeQty, LogRow::setChangeQty),
            ExcelColumn.integer("结存", LogRow::getBalanceQty, LogRow::setBalanceQty),
            ExcelColumn.text("操作人", LogRow::getCreateBy, LogRow::setCreateBy),
            ExcelColumn.text("时间", LogRow::getCreateTime, LogRow::setCreateTime));

    byte[] exportLogs(List<FgStockLogRowVO> list) {
        return Excel.export(LOG, list.stream().map(l -> {
            LogRow r = new LogRow();
            r.setBizDate(l.getBizDate());
            r.setChangeType(l.getChangeType().getLabel());
            r.setDocNo(l.getDocNo());
            r.setWarehouse(l.getWarehouseName());
            r.setSkuCode(l.getSkuCode());
            r.setStyleCode(l.getStyleCode());
            r.setColorName(l.getColorName());
            r.setSizeCode(l.getSizeCode());
            r.setChangeQty(l.getChangeQty());
            r.setBalanceQty(l.getBalanceQty());
            r.setCreateBy(l.getCreateByName());
            r.setCreateTime(l.getCreateTime() == null ? null : TIME.format(l.getCreateTime()));
            return r;
        }).toList());
    }
}
