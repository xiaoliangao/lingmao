package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelImport;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderStyleRef;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.entity.StyleStatus;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 需求订单的导入、导出（docs/api/02 §3.9、§3.10，PRD §6.5.2）。
 * 导入一行一个 SKU：同一个分单号的行进同一张订单，分单号都不填整个文件一张；款号、颜色（名称或编码）、尺码要对得上一个启用的 SKU。
 * 有一处不对整批不导（1003）。资料包不在这里查：导进来是草稿，提交时再查
 */
@Component
@RequiredArgsConstructor
class OrderExcel {

    private static final String GROUP = "分单号";
    private static final String CONTRACT = "合同号";
    private static final String STYLE = "款号";
    private static final String COLOR = "颜色";
    private static final String SIZE = "尺码";

    private final StyleService styleService;
    private final DictDataService dictDataService;

    @Data
    static class ImportRow {
        private String group;
        private String contractNo;
        private String styleCode;
        private String color;
        private String size;
        private Integer qty;
        private BigDecimal price;
        private LocalDate lineDueDate;
    }

    private static final ExcelSchema<ImportRow> IMPORT = ExcelSchema.of("需求订单", ImportRow::new,
            ExcelColumn.text(GROUP, ImportRow::getGroup, ImportRow::setGroup).code().maxLength(32)
                    .note("同一个分单号的行进同一张订单；都不填整个文件一张").example("A"),
            ExcelColumn.text(CONTRACT, ImportRow::getContractNo, ImportRow::setContractNo).code().maxLength(64)
                    .note("同一张订单的行要一样"),
            ExcelColumn.text(STYLE, ImportRow::getStyleCode, ImportRow::setStyleCode).required().code().example("27CF001"),
            ExcelColumn.text(COLOR, ImportRow::getColor, ImportRow::setColor).required().note("颜色名称或编码").example("卡其"),
            ExcelColumn.text(SIZE, ImportRow::getSize, ImportRow::setSize).required().code().example("M"),
            ExcelColumn.integer("数量", ImportRow::getQty, ImportRow::setQty).required()
                    .check(v -> v <= 0 ? "要大于 0" : null).example("120"),
            ExcelColumn.decimal("单价", 4, ImportRow::getPrice, ImportRow::setPrice).nonNegative().example("89"),
            ExcelColumn.date("行交期", ImportRow::getLineDueDate, ImportRow::setLineDueDate)
                    .note("不填用导入对话框里的要求交期"));

    /** 导进来的一行，对上了 SKU */
    record Line(int row, long styleId, String styleCode, long styleSkuId, int qty, BigDecimal price, LocalDate lineDueDate) {
    }

    /** 一张订单：同一个分单号的行 */
    record Parsed(String contractNo, List<Line> lines) {
    }

    byte[] template() {
        return Excel.template(IMPORT);
    }

    /** 读文件、对款式，按分单号分好；有一处不对抛 1003 */
    List<Parsed> read(MultipartFile file) {
        ExcelImport<ImportRow> sheet = Excel.read(IMPORT, file);
        List<ExcelImport.Line<ImportRow>> rows = sheet.lines();
        Map<String, Long> styleIds = styleService.idsByCodes(rows.stream().map(l -> l.data().getStyleCode())
                .filter(Objects::nonNull).toList());
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(styleIds.values());

        Map<String, List<Line>> groups = new LinkedHashMap<>();
        Map<String, String> contracts = new HashMap<>();
        Map<String, Integer> contractRows = new HashMap<>();
        Map<String, Integer> firstRowOfSku = new HashMap<>();
        for (ExcelImport.Line<ImportRow> l : rows) {
            ImportRow r = l.data();
            try {
                NumericLimits.price(r.getPrice(), "");
                if (r.getQty() != null) NumericLimits.amount(Num.amount(r.getQty(), r.getPrice()), "行");
            } catch (BizException e) {
                sheet.error(l.row(), "单价/金额", e.getMessage());
            }
            String group = Objects.requireNonNullElse(r.getGroup(), "");
            if (r.getContractNo() != null) {
                String first = contracts.putIfAbsent(group, r.getContractNo());
                contractRows.putIfAbsent(group, l.row());
                if (first != null && !first.equals(r.getContractNo())) {
                    sheet.error(l.row(), CONTRACT, "同一张订单的合同号要一样：第 " + contractRows.get(group) + " 行是 " + first);
                }
            }
            if (r.getStyleCode() == null || r.getColor() == null || r.getSize() == null || r.getQty() == null) {
                continue;   // 那一格的问题已经记下了
            }
            Long styleId = styleIds.get(r.getStyleCode().toUpperCase(Locale.ROOT));
            StyleOrderInfo info = styleId == null ? null : infos.get(styleId);
            if (info == null) {
                sheet.error(l.row(), STYLE, "款号不存在");
                continue;
            }
            if (info.status() != StyleStatus.ENABLED) {
                sheet.error(l.row(), STYLE, "款式现在是「" + info.status().getLabel() + "」，启用后才能下单");
                continue;
            }
            StyleOrderInfo.Color color = info.colors().stream()
                    .filter(c -> r.getColor().equals(c.name()) || r.getColor().equalsIgnoreCase(c.code()))
                    .findFirst().orElse(null);
            if (color == null) {
                sheet.error(l.row(), COLOR, "款上没有这个颜色");
                continue;
            }
            if (!color.enabled()) {
                sheet.error(l.row(), COLOR, "颜色在款上停用了");
                continue;
            }
            String size = info.sizes().stream().filter(sz -> sz.equalsIgnoreCase(r.getSize())).findFirst().orElse(null);
            if (size == null) {
                sheet.error(l.row(), SIZE, "尺码组里没有这个尺码：" + String.join("、", info.sizes()));
                continue;
            }
            StyleOrderInfo.Sku sku = info.skus().stream()
                    .filter(k -> k.colorId() == color.id() && k.sizeCode().equals(size)).findFirst().orElse(null);
            if (sku == null) {
                sheet.error(l.row(), SIZE, "款式还没生成这个 SKU：先在款式里生成");
                continue;
            }
            if (!sku.enabled()) {
                sheet.error(l.row(), SIZE, sku.code() + " 已停用");
                continue;
            }
            Integer first = firstRowOfSku.putIfAbsent(group + "\n" + sku.id(), l.row());
            if (first != null) {
                sheet.error(l.row(), null, "和第 " + first + " 行是同一个 SKU（" + sku.code() + "），合成一行");
                continue;
            }
            groups.computeIfAbsent(group, g -> new ArrayList<>())
                    .add(new Line(l.row(), info.id(), info.code(), sku.id(), r.getQty(), r.getPrice(), r.getLineDueDate()));
        }
        groups.forEach((group, lines) -> {
            long qty = 0;
            BigDecimal amount = BigDecimal.ZERO;
            for (Line line : lines) {
                qty += line.qty();
                BigDecimal value = Num.amount(line.qty(), line.price());
                if (value != null) amount = amount.add(value);
                try {
                    NumericLimits.pieces(qty, "分单" + group + "：");
                    NumericLimits.amount(amount, "分单" + group + "合计：");
                } catch (BizException e) {
                    sheet.error(line.row(), "分单合计", e.getMessage());
                    break;
                }
            }
        });
        sheet.dataOrThrow();
        return groups.entrySet().stream().map(e -> new Parsed(contracts.get(e.getKey()), e.getValue())).toList();
    }

    @Data
    static class ExportRow {
        private String no;
        private LocalDate orderDate;
        private String orderType;
        private String contractNo;
        private String brand;
        private String styleCodes;
        private Integer qty;
        private BigDecimal amount;
        private String currency;
        private LocalDate requiredDate;
        private LocalDate commitDate;
        private Integer scheduledQty;
        private Integer returnedQty;
        private String sales;
        private String merch;
        private String company;
        private String source;
        private String status;
    }

    private static final ExcelSchema<ExportRow> EXPORT = ExcelSchema.of("需求订单", ExportRow::new,
            ExcelColumn.text("订单号", ExportRow::getNo, ExportRow::setNo),
            ExcelColumn.date("下单日期", ExportRow::getOrderDate, ExportRow::setOrderDate),
            ExcelColumn.text("类型", ExportRow::getOrderType, ExportRow::setOrderType),
            ExcelColumn.text("合同号", ExportRow::getContractNo, ExportRow::setContractNo),
            ExcelColumn.text("品牌", ExportRow::getBrand, ExportRow::setBrand),
            ExcelColumn.text("款号", ExportRow::getStyleCodes, ExportRow::setStyleCodes),
            ExcelColumn.integer("数量", ExportRow::getQty, ExportRow::setQty),
            ExcelColumn.decimal("金额", 2, ExportRow::getAmount, ExportRow::setAmount),
            ExcelColumn.text("币种", ExportRow::getCurrency, ExportRow::setCurrency),
            ExcelColumn.date("要求交期", ExportRow::getRequiredDate, ExportRow::setRequiredDate),
            ExcelColumn.date("承诺交期", ExportRow::getCommitDate, ExportRow::setCommitDate),
            ExcelColumn.integer("已排产", ExportRow::getScheduledQty, ExportRow::setScheduledQty),
            ExcelColumn.integer("已回货", ExportRow::getReturnedQty, ExportRow::setReturnedQty),
            ExcelColumn.text("业务员", ExportRow::getSales, ExportRow::setSales),
            ExcelColumn.text("跟单员", ExportRow::getMerch, ExportRow::setMerch),
            ExcelColumn.text("主体", ExportRow::getCompany, ExportRow::setCompany),
            ExcelColumn.text("来源", ExportRow::getSource, ExportRow::setSource),
            ExcelColumn.text("状态", ExportRow::getStatus, ExportRow::setStatus));

    /** 一张订单一行；行上的款式、人名已经由 Service 填好 */
    byte[] export(List<OrderRowVO> orders) {
        Map<String, String> types = labels("order_type");
        Map<String, String> brands = labels("brand");
        Map<String, String> currencies = labels("currency");
        List<ExportRow> rows = new ArrayList<>();
        for (OrderRowVO o : orders) {
            ExportRow r = new ExportRow();
            r.setNo(o.getNo());
            r.setOrderDate(o.getOrderDate());
            r.setOrderType(types.getOrDefault(o.getOrderType(), o.getOrderType()));
            r.setContractNo(o.getContractNo());
            r.setBrand(o.getBrand() == null ? null : brands.getOrDefault(o.getBrand(), o.getBrand()));
            r.setStyleCodes(o.getStyles().stream().map(OrderStyleRef::styleCode).filter(Objects::nonNull)
                    .collect(Collectors.joining("、")));
            r.setQty(o.getTotalQty());
            r.setAmount(o.getTotalAmount());
            r.setCurrency(currencies.getOrDefault(o.getCurrency(), o.getCurrency()));
            r.setRequiredDate(o.getRequiredDate());
            r.setCommitDate(o.getCommitDate());
            r.setScheduledQty(o.getScheduledQty());
            r.setReturnedQty(o.getReturnedQty());
            r.setSales(o.getSalesName());
            r.setMerch(o.getMerchName());
            r.setCompany(o.getCompanyName());
            r.setSource(o.getSource() == null ? null : o.getSource().getLabel());
            r.setStatus(OrderServiceImpl.ORDER_STATUS.label(DocStatus.valueOf(o.getStatus())).text());
            rows.add(r);
        }
        return Excel.export(EXPORT, rows);
    }

    /** 字典 value → 中文（停用的也翻） */
    private Map<String, String> labels(String type) {
        return dictDataService.listByType(type).stream().collect(Collectors.toMap(DictOption::value, DictOption::label, (a, b) -> a));
    }
}
