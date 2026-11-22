package com.lingmao.scm.module.production.service.impl;

import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.dto.WarehouseVO;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.ActionRequestService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.production.dto.CutQtyReq;
import com.lingmao.scm.module.production.dto.ProductionReceiptVO;
import com.lingmao.scm.module.production.dto.ReceiptCreated;
import com.lingmao.scm.module.production.dto.ReceiptForStock;
import com.lingmao.scm.module.production.dto.ReceiptItemVO;
import com.lingmao.scm.module.production.dto.ReceiptOverRow;
import com.lingmao.scm.module.production.dto.ReceiptReq;
import com.lingmao.scm.module.production.entity.ProductionOrder;
import com.lingmao.scm.module.production.entity.ProductionOrderItem;
import com.lingmao.scm.module.production.entity.ProductionReceipt;
import com.lingmao.scm.module.production.entity.ProductionReceiptItem;
import com.lingmao.scm.module.production.mapper.ProductionOrderItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptItemMapper;
import com.lingmao.scm.module.production.mapper.ProductionReceiptMapper;
import com.lingmao.scm.module.production.service.ProductionReceiptService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 回货（docs/api/05 §3.6）："登记"一步 = 建 + 审核，一个事务里建单、回写生产单明细和订单行的已回货、重算两边的状态；
 * 作废一步完成（减回去再作废）。都先锁订单、再锁生产单，两个人同时登记按先后查上限
 */
@Service
@RequiredArgsConstructor
public class ProductionReceiptServiceImpl implements ProductionReceiptService {

    private final ProductionReceiptMapper receiptMapper;
    private final ProductionReceiptItemMapper receiptItemMapper;
    private final ProductionOrderItemMapper itemMapper;
    private final ProductionSupport support;
    private final OrderService orderService;
    private final StyleService styleService;
    private final WarehouseService warehouseService;
    private final AttachmentService attachmentService;
    private final ReferenceService referenceService;
    private final UserService userService;
    private final SerialNoGenerator serialNoGenerator;
    private final ActionRequestService actionRequestService;
    private final Clock clock;

    @Override
    public List<ProductionReceiptVO> list(long prdOrderId) {
        ProductionOrder p = support.getVisible(prdOrderId);
        List<ProductionReceipt> receipts = receiptMapper.selectByOrder(prdOrderId);
        if (receipts.isEmpty()) {
            return List.of();
        }
        List<Long> ids = receipts.stream().map(ProductionReceipt::getId).toList();
        Map<Long, List<ProductionReceiptItem>> items = receiptItemMapper.selectByReceipts(ids).stream()
                .collect(Collectors.groupingBy(ProductionReceiptItem::getReceiptId));
        Map<Long, List<AttachmentVO>> photos = attachmentService.lists(BizType.PRODUCTION_RECEIPT, ids, Attachment.IMAGE);
        Set<Long> userIds = new HashSet<>();
        receipts.forEach(r -> {
            userIds.add(r.getCreateBy());
            userIds.add(r.getVoidBy());
        });
        userIds.remove(null);
        Map<Long, String> names = new HashMap<>(userService.nicknames(userIds));   // 没作废的 voidBy 是 null
        Map<Long, String> warehouses = warehouseService.names(receipts.stream().map(ProductionReceipt::getWarehouseId).toList());
        StyleOrderInfo style = styleService.orderInfos(List.of(p.getStyleId())).get(p.getStyleId());
        boolean canVoid = LoginUser.hasAuthority("production:receipt:void") && p.getStatus() != CLOSED && p.getStatus() != VOIDED;

        List<ProductionReceiptVO> out = new ArrayList<>();
        for (ProductionReceipt r : receipts) {
            List<ReceiptItemVO> rows = items.getOrDefault(r.getId(), List.of()).stream().map(i -> itemVO(i, style)).toList();
            ProductionReceiptVO v = new ProductionReceiptVO();
            v.setId(r.getId());
            v.setNo(r.getNo());
            v.setStatus(r.getStatus().name());
            v.setReceiptDate(r.getReceiptDate());
            v.setWarehouseId(r.getWarehouseId());
            v.setWarehouseName(r.getWarehouseId() == null ? null : warehouses.get(r.getWarehouseId()));
            v.setLogisticsNo(r.getLogisticsNo());
            v.setRemark(r.getRemark());
            v.setQty(rows.stream().mapToInt(ReceiptItemVO::getQty).sum());
            v.setDefectQty(rows.stream().mapToInt(ReceiptItemVO::getDefectQty).sum());
            v.setPhotos(photos.getOrDefault(r.getId(), List.of()));
            v.setCreateByName(names.get(r.getCreateBy()));
            v.setCreateTime(r.getCreateTime());
            v.setVoidReason(r.getVoidReason());
            v.setVoidByName(names.get(r.getVoidBy()));
            v.setVoidTime(r.getVoidTime());
            v.setItems(rows);
            // 入过成品仓的（有没作废的成品入库单）不给作废，要先处理成品入库单（RFC 008 §6.2）
            v.setActions(canVoid && r.getStatus() == APPROVED && referenceService.referrers(BizType.PRODUCTION_RECEIPT, r.getId()).isEmpty()
                    ? List.of("void") : List.of());
            out.add(v);
        }
        return out;
    }

    private static ReceiptItemVO itemVO(ProductionReceiptItem i, StyleOrderInfo style) {
        StyleOrderInfo.Sku sku = style.skus().stream().filter(s -> s.id() == i.getStyleSkuId()).findFirst().orElse(null);
        StyleOrderInfo.Color color = sku == null ? null
                : style.colors().stream().filter(c -> c.id() == sku.colorId()).findFirst().orElse(null);
        ReceiptItemVO v = new ReceiptItemVO();
        v.setId(i.getId());
        v.setPrdOrderItemId(i.getPrdOrderItemId());
        v.setStyleSkuId(i.getStyleSkuId());
        v.setSkuCode(sku == null ? null : sku.code());
        v.setSizeCode(sku == null ? null : sku.sizeCode());
        if (color != null) {
            v.setColorCode(color.code());
            v.setColorName(color.name());
            v.setColorHex(color.hex());
        }
        v.setQty(i.getQty());
        v.setDefectQty(i.getDefectQty());
        v.setGoodQty(i.getQty() - i.getDefectQty());
        v.setRemark(i.getRemark());
        return v;
    }

    @Override
    @Transactional
    public ReceiptCreated receive(long prdOrderId, ReceiptReq req) {
        if (!LoginUser.hasAuthority("production:receipt:add")) throw new BizException(403, "没有登记回货的权限");
        support.getVisible(prdOrderId);
        return actionRequestService.execute("PRODUCTION_RECEIPT", prdOrderId, req.getClientActionId(), req, ReceiptCreated.class,
                () -> receiveNew(prdOrderId, req));
    }

    ReceiptCreated receiveNew(long prdOrderId, ReceiptReq req) {
        ProductionSupport.Locked l = support.lockWithOrder(prdOrderId);
        ProductionOrder p = l.prd();
        if (!ProductionRules.FLOW.allows("receive", p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，不能登记回货");
        }
        if (req.getReceiptDate().isAfter(support.today())) {
            throw new BizException(400, "回货日期不能晚于今天");
        }
        List<ReceiptReq.Item> rows = req.getItems().stream().filter(i -> i.getQty() > 0).toList();
        if (rows.isEmpty()) {
            throw new BizException(400, "一个数都没填");
        }
        if (req.getWarehouseId() != null) {
            WarehouseVO w = warehouseService.getById(req.getWarehouseId());
            if (!Integer.valueOf(1).equals(w.getStatus())) {
                throw new BizException(400, "收货仓" + w.getName() + "已停用，请换一个");
            }
        }
        Map<Long, ProductionOrderItem> lines = itemMapper.selectByOrder(prdOrderId).stream()
                .collect(Collectors.toMap(ProductionOrderItem::getId, Function.identity()));
        StyleOrderInfo style = styleService.orderInfos(List.of(p.getStyleId())).get(p.getStyleId());
        Set<Long> seen = new HashSet<>();
        List<ReceiptOverRow> over = new ArrayList<>();
        for (int n = 0; n < rows.size(); n++) {
            ReceiptReq.Item r = rows.get(n);
            ProductionOrderItem line = lines.get(r.getPrdOrderItemId());
            if (line == null || !seen.add(r.getPrdOrderItemId())) {
                throw new BizException(400, "明细和这张单对不上，请刷新后再登记");
            }
            if (r.getDefectQty() > r.getQty()) {
                throw new BizException(400, skuCode(style, line.getStyleSkuId()) + "：次品不能多于回货");
            }
            NumericLimits.pieces((long) line.getReturnedQty() + r.getQty(), "第 " + (n + 1) + " 行累计回货：");
            int good = ProductionRules.good(line);
            int max = ProductionRules.maxReturn(line);
            if (good + r.getQty() - r.getDefectQty() > max) {
                int left = Math.max(max - good, 0);
                String basis = line.getCutQty() != null ? "裁床 " + line.getCutQty() + " 件" : "加工 " + line.getQty() + " 件";
                over.add(new ReceiptOverRow(skuCode(style, line.getStyleSkuId()), line.getQty(), good, r.getQty() - r.getDefectQty(), max,
                        basis + "，已回良品 " + good + "，" + (left == 0 ? "良品不能再回了" : "良品最多还能回 " + left + " 件")));
            }
        }
        if (!over.isEmpty()) {
            throw new BizException(ErrorCodes.OVER_LIMIT, "有 " + over.size() + " 个 SKU 回多了：每个 SKU 的良品最多回到件数（登记了裁床数按裁床数）的 105%", over);
        }
        int qty = NumericLimits.pieces(rows.stream().mapToLong(ReceiptReq.Item::getQty).sum(), "本次回货：");
        int defect = NumericLimits.pieces(rows.stream().mapToLong(ReceiptReq.Item::getDefectQty).sum(), "本次次品：");
        NumericLimits.pieces(lines.values().stream().mapToLong(ProductionOrderItem::getReturnedQty).sum() + qty, "生产单累计回货：");
        NumericLimits.pieces(l.order().items().stream().mapToLong(OrderForProduction.Line::returnedQty).sum() + qty, "需求订单累计回货：");

        ProductionReceipt receipt = new ProductionReceipt();
        receipt.setNo(serialNoGenerator.daily(BizType.PRODUCTION_RECEIPT.prefix(null)));
        receipt.setCompanyId(p.getCompanyId());
        receipt.setPrdOrderId(prdOrderId);
        receipt.setReceiptDate(req.getReceiptDate());
        receipt.setWarehouseId(req.getWarehouseId());
        receipt.setLogisticsNo(ProdTexts.blankToNull(req.getLogisticsNo()));
        receipt.setRemark(ProdTexts.blankToNull(req.getRemark()));
        receipt.setStatus(APPROVED);
        receipt.setAuditBy(LoginUser.currentUserId());
        receipt.setAuditTime(OffsetDateTime.now(clock));
        receiptMapper.insert(receipt);

        List<ProductionReceiptItem> items = new ArrayList<>();
        Map<Long, Integer> toOrder = new HashMap<>();
        for (int n = 0; n < rows.size(); n++) {
            ReceiptReq.Item r = rows.get(n);
            ProductionOrderItem line = lines.get(r.getPrdOrderItemId());
            ProductionReceiptItem item = new ProductionReceiptItem();
            item.setReceiptId(receipt.getId());
            item.setSeq(n + 1);
            item.setPrdOrderItemId(line.getId());
            item.setStyleSkuId(line.getStyleSkuId());
            item.setQty(r.getQty());
            item.setDefectQty(r.getDefectQty());
            item.setRemark(ProdTexts.blankToNull(r.getRemark()));
            items.add(item);
            if (itemMapper.addReturned(line.getId(), r.getQty(), r.getDefectQty(), ProductionRules.maxReturn(line)) == 0) {
                throw new BizException(ErrorCodes.STATE_CHANGED, "刚有人登记了回货，请刷新后再登记");
            }
            toOrder.merge(line.getOrderItemId(), r.getQty() - r.getDefectQty(), Integer::sum);   // 订单只记良品
        }
        receiptItemMapper.insert(items);
        attachmentService.replace(BizType.PRODUCTION_RECEIPT, receipt.getId(), Attachment.IMAGE, req.getPhotos());
        orderService.addReturned(toOrder);
        String text = receipt.getNo() + " 回货 " + qty + " 件" + (defect > 0 ? "（次品 " + defect + "）" : "");
        DocStatus to = support.refreshStatus(p, "receive", text);
        support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 登记回货 " + receipt.getNo());
        return new ReceiptCreated(receipt.getId(), receipt.getNo(), to.name());
    }

    private static String skuCode(StyleOrderInfo style, long styleSkuId) {
        return style.skus().stream().filter(s -> s.id() == styleSkuId).map(StyleOrderInfo.Sku::code).findFirst()
                .orElse(String.valueOf(styleSkuId));
    }

    @Override
    @Transactional
    public void registerCut(long prdOrderId, CutQtyReq req) {
        support.getVisible(prdOrderId);
        ProductionSupport.Locked l = support.lockWithOrder(prdOrderId);
        ProductionOrder p = l.prd();
        if (!ProductionRules.FLOW.allows("cut", p.getStatus())) {
            throw new BizException(ProductionSupport.label(p) + " 现在是「" + ProductionRules.STATUS.label(p.getStatus()).text()
                    + "」，已下单、生产中才能登记裁床数");
        }
        Map<Long, ProductionOrderItem> lines = itemMapper.selectByOrder(prdOrderId).stream()
                .collect(Collectors.toMap(ProductionOrderItem::getId, Function.identity()));
        StyleOrderInfo style = styleService.orderInfos(List.of(p.getStyleId())).get(p.getStyleId());
        Set<Long> seen = new HashSet<>();
        List<String> changed = new ArrayList<>();
        for (CutQtyReq.Item r : req.getItems()) {
            ProductionOrderItem line = lines.get(r.getPrdOrderItemId());
            if (line == null || !seen.add(r.getPrdOrderItemId())) {
                throw new BizException(400, "明细和这张单对不上，请刷新后再登记");
            }
            Integer cut = r.getCutQty();
            if (Objects.equals(cut, line.getCutQty())) {
                continue;
            }
            String sku = skuCode(style, line.getStyleSkuId());
            if (cut != null && cut > ProductionRules.maxPieces(line.getQty())) {
                throw new BizException(400, sku + "：裁床数最多 " + ProductionRules.maxPieces(line.getQty()) + "（件数 " + line.getQty() + " 的 105%）");
            }
            int least = ProductionRules.minCut(ProductionRules.good(line));
            if (cut != null && cut < least) {
                throw new BizException(400, sku + "：已经回了良品 " + ProductionRules.good(line) + " 件，裁床数不能少于 " + least);
            }
            itemMapper.updateCut(line.getId(), cut);
            changed.add(sku + " " + cutText(line.getCutQty()) + " → " + cutText(cut));
        }
        if (changed.isEmpty()) {
            return;
        }
        DocStatus before = p.getStatus();
        DocStatus to = support.refreshStatus(p, "cut", "登记裁床数：" + String.join("，", changed));
        if (to != before) {
            support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 登记裁床数");
        }
    }

    private static String cutText(Integer cut) {
        return cut == null ? "未登记" : String.valueOf(cut);
    }

    @Override
    public ReceiptForStock forStock(long receiptId) {
        ProductionReceipt r = receiptMapper.lockById(receiptId);
        ProductionOrder p = r == null ? null : support.getUnscoped(r.getPrdOrderId());
        if (p == null) {
            throw new BizException(404, "回货单不存在");
        }
        if (r.getStatus() != APPROVED) {
            throw new BizException("回货单 " + r.getNo() + " 已作废，不能入成品仓");
        }
        com.lingmao.scm.common.scope.CompanyContext.target(r.getCompanyId());
        List<ReceiptForStock.Item> items = receiptItemMapper.selectByReceipts(List.of(receiptId)).stream()
                .map(i -> new ReceiptForStock.Item(i.getId(), i.getStyleSkuId(), i.getQty(), i.getDefectQty()))
                .toList();
        return new ReceiptForStock(r.getId(), r.getNo(), r.getCompanyId(), p.getId(), p.getNo(), p.getKind().name(), p.getStyleId(),
                p.getFactoryId(), r.getReceiptDate(), r.getWarehouseId(), items);
    }

    @Override
    @Transactional
    public void voidReceipt(long prdOrderId, long receiptId, String reason) {
        String why = ProdTexts.blankToNull(reason);
        if (why == null) {
            throw new BizException(400, "请写作废原因");
        }
        ProductionSupport.Locked l = support.lockWithOrder(prdOrderId);
        ProductionOrder p = l.prd();
        ProductionReceipt r = receiptMapper.selectById(receiptId);
        if (r == null || r.getPrdOrderId() != prdOrderId) {
            throw new BizException(404, "回货单不存在");
        }
        if (r.getStatus() != APPROVED) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "回货单 " + r.getNo() + " 已经作废了，请刷新");
        }
        if (p.getStatus() == CLOSED || p.getStatus() == VOIDED) {
            throw new BizException(ProductionSupport.label(p) + " 已结案，回货不能作废");
        }
        // 先改状态（锁住回货单）再查引用（RFC 008 §6.2）：正在建的成品入库单拿着回货单的锁，这里等它提交以后查得到它（1005）；
        // 反过来成品那边等这里提交以后看到的是已作废
        StatusCas.update(receiptMapper, new ProductionReceipt(), StatusCas.of(ProductionReceipt::getId, receiptId,
                        ProductionReceipt::getStatus, APPROVED, VOIDED)
                .set(ProductionReceipt::getVoidReason, why)
                .set(ProductionReceipt::getVoidBy, LoginUser.currentUserId())
                .set(ProductionReceipt::getVoidTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1"));
        referenceService.assertNoReferrer(BizType.PRODUCTION_RECEIPT, receiptId, "作废");
        Map<Long, ProductionOrderItem> lines = itemMapper.selectByOrder(prdOrderId).stream()
                .collect(Collectors.toMap(ProductionOrderItem::getId, Function.identity()));
        Map<Long, Integer> toOrder = new HashMap<>();
        for (ProductionReceiptItem i : receiptItemMapper.selectByReceipts(List.of(receiptId))) {
            ProductionOrderItem line = lines.get(i.getPrdOrderItemId());
            if (itemMapper.addReturned(line.getId(), -i.getQty(), -i.getDefectQty(), Integer.MAX_VALUE) == 0) {
                throw new IllegalStateException("回货单 " + r.getNo() + " 作废时已回货减成了负数：明细 " + line.getId());
            }
            toOrder.merge(line.getOrderItemId(), -(i.getQty() - i.getDefectQty()), Integer::sum);
        }
        orderService.addReturned(toOrder);
        String text = "回货 " + r.getNo() + " 作废（" + why + "）";
        support.refreshStatus(p, "void-receipt", text);
        support.syncOrder(p.getOrderId(), ProductionSupport.label(p) + " 的" + text);
    }
}
