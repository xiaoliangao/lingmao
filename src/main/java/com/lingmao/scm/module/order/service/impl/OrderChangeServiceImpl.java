package com.lingmao.scm.module.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.StatusCount;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.DocNoticeService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.dto.ChangeItemVO;
import com.lingmao.scm.module.order.dto.OrderChangeCreateReq;
import com.lingmao.scm.module.order.dto.OrderChangeDetailVO;
import com.lingmao.scm.module.order.dto.OrderChangeForm;
import com.lingmao.scm.module.order.dto.OrderChangeQuery;
import com.lingmao.scm.module.order.dto.OrderChangeRowVO;
import com.lingmao.scm.module.order.dto.OrderChangeSaveReq;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import com.lingmao.scm.module.order.entity.OrderItem;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import com.lingmao.scm.module.order.mapper.OrderChangeItemMapper;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.order.mapper.OrderItemMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.order.service.OrderChangeService;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.dto.PackRequirement;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 订单变更单（docs/api/02 §2.2、§3.14，PRD §6.5.6）。表单是改完以后的样子，diff 和订单现在的数比出改了哪几处；
 * check 在新建、改草稿、提交时各跑一遍。单级审核：审过由 OrderService.applyChange 写回订单，再给下游单据发变更提醒。
 * <p>
 * 加锁顺序：先变更单、再订单（applyChange 锁订单）；订单那边的动作不锁变更单，不会互相等
 */
@Service
@RequiredArgsConstructor
public class OrderChangeServiceImpl implements OrderChangeService {

    /** 状态转换表（docs/api/02 §2.2）。审过不能反审核：要改回去再提一张 */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(APPROVED)
            .on("void", DRAFT, PENDING).to(VOIDED)
            .on("remove", DRAFT).to(DRAFT)
            .build();

    static final String PERM = "order:change:";

    private static final String NOT_FOUND = "变更单不存在";
    private static final String STALE = "这张变更单已经被别人改过，请刷新后再改";
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final OrderChangeMapper changeMapper;
    private final OrderChangeItemMapper changeItemMapper;
    private final OrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final OrderService orderService;
    private final ChangeTexts changeTexts;
    private final StyleService styleService;
    private final TechPackService techPackService;
    private final CompanyService companyService;
    private final UserService userService;
    private final TradeFeature tradeFeature;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final DocNoticeService docNoticeService;
    private final AttachmentService attachmentService;
    private final ReferenceService referenceService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;

    // ---------- 查询 ----------

    @Override
    public PageResult<OrderChangeRowVO> page(OrderChangeQuery query) {
        IPage<OrderChangeRowVO> page = changeMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public Map<String, Long> counts(OrderChangeQuery query) {
        return StatusCount.toMap(changeMapper.selectCounts(query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine())));
    }

    private void fillRows(List<OrderChangeRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, List<OrderChangeItem>> items = changeItemMapper.selectByChanges(rows.stream().map(OrderChangeRowVO::getId).toList())
                .stream().collect(Collectors.groupingBy(OrderChangeItem::getChangeId));
        ChangeTexts.Names n = changeTexts.names(items.values().stream().flatMap(List::stream).toList());
        Set<Long> userIds = new HashSet<>();
        rows.forEach(r -> {
            userIds.add(r.getCreateBy());
            userIds.add(r.getAuditBy());
        });
        userIds.remove(null);
        Map<Long, String> names = userService.nicknames(userIds);
        for (OrderChangeRowVO r : rows) {
            r.setChangeTypes(types(r.getChangeTypesText()));
            List<String> texts = items.getOrDefault(r.getId(), List.of()).stream().map(ci -> ChangeTexts.text(ci, n, null)).toList();
            r.setSummary(ChangeTexts.summary(r.getChangeTypes().contains(ChangeType.CANCEL), texts));
            r.setCreateByName(names.get(r.getCreateBy()));
            r.setAuditByName(r.getAuditBy() == null ? null : names.get(r.getAuditBy()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), isCreator(r.getCreateBy())));
        }
    }

    /** 库里逗号分隔的变更类型 */
    static List<ChangeType> types(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split(",")).map(ChangeType::valueOf).toList();
    }

    /** 存库的变更类型：取消订单就只有 CANCEL，不然按明细里出现的、照枚举的顺序 */
    static String typesText(boolean cancel, List<OrderChangeItem> items) {
        if (cancel) {
            return ChangeType.CANCEL.name();
        }
        return items.stream().map(OrderChangeItem::getChangeType).distinct().sorted().map(Enum::name)
                .collect(Collectors.joining(","));
    }

    /** 按钮：撤回只给制单人；改、删看 order:change:edit、:remove */
    static List<String> actions(DocStatus status, boolean creator) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("remove", PERM + "remove")
                .when("withdraw", creator)
                .list();
    }

    private static boolean isCreator(Long createBy) {
        return createBy != null && createBy.equals(LoginUser.currentUserId());
    }

    @Override
    public OrderChangeDetailVO detail(long id) {
        OrderChange c = changeMapper.selectById(id);
        Order o = visible(c);
        List<OrderChangeItem> items = changeItemMapper.selectByChange(id);
        ChangeTexts.Names n = changeTexts.names(items);
        // 没审完的，数量那几处带上现在的已排产
        boolean open = c.getStatus() == DRAFT || c.getStatus() == PENDING;
        Map<Long, OrderItem> bySku = open ? bySku(itemMapper.selectByOrder(o.getId())) : Map.of();

        OrderChangeDetailVO vo = docHeaderService.fill(new OrderChangeDetailVO(), BizType.ORDER_CHANGE, c, c.getStatus());
        vo.setCompanyName(companyService.getById(c.getCompanyId()).getShortName());
        vo.setOrderId(o.getId());
        vo.setOrderNo(o.getNo());
        vo.setOrderStatus(o.getStatus().name());
        List<ChangeType> types = types(c.getChangeTypes());
        boolean cancel = types.contains(ChangeType.CANCEL);
        vo.setChangeTypes(types);
        vo.setCancel(cancel);
        vo.setReason(c.getReason());
        vo.setResponsibleParty(c.getResponsibleParty());
        vo.setFeeNote(c.getFeeNote());
        vo.setRejectReason(c.getStatus() == DRAFT ? c.getRejectReason() : null);
        vo.setItems(items.stream().map(ci -> {
            OrderItem row = ci.getStyleSkuId() == null ? null : bySku.get(ci.getStyleSkuId());
            return itemVO(ci, n, row == null ? null : row.getScheduledQty());
        }).toList());
        vo.setSummary(ChangeTexts.summary(cancel, items.stream().map(ci -> ChangeTexts.text(ci, n, null)).toList()));
        vo.setActions(actions(c.getStatus(), isCreator(c.getCreateBy())));
        return vo;
    }

    private static ChangeItemVO itemVO(OrderChangeItem ci, ChangeTexts.Names n, Integer scheduled) {
        ChangeItemVO v = new ChangeItemVO();
        v.setId(ci.getId());
        v.setSeq(ci.getSeq());
        v.setChangeType(ci.getChangeType());
        v.setOrderItemId(ci.getOrderItemId());
        v.setStyleId(ci.getStyleId());
        v.setStyleCode(n.styleCodes().get(ci.getStyleId()));
        v.setStyleSkuId(ci.getStyleSkuId());
        v.setSkuCode(ci.getStyleSkuId() == null ? null : n.skuCodes().get(ci.getStyleSkuId()));
        v.setFromQty(ci.getFromQty());
        v.setToQty(ci.getToQty());
        v.setFromDate(ci.getFromDate());
        v.setToDate(ci.getToDate());
        v.setFromPrice(ci.getFromPrice());
        v.setToPrice(ci.getToPrice());
        v.setFromTechPackId(ci.getFromTechPackId());
        v.setFromVersion(version(n, ci.getFromTechPackId()));
        v.setToTechPackId(ci.getToTechPackId());
        v.setToVersion(version(n, ci.getToTechPackId()));
        v.setText(ChangeTexts.text(ci, n, scheduled));
        return v;
    }

    private static Integer version(ChangeTexts.Names n, Long packId) {
        PackBrief p = packId == null ? null : n.packs().get(packId);
        return p == null ? null : p.version();
    }

    @Override
    public void checkVisible(long id) {
        visible(changeMapper.selectById(id));
    }

    /** 能看变更单 = 能看它的订单（制单人、业务员、跟单员），或者是变更单的制单人；返回订单 */
    private Order visible(OrderChange c) {
        if (c == null) {
            throw new BizException(404, NOT_FOUND);
        }
        Order o = orderMapper.selectById(c.getOrderId());
        DataScope.current(false).check(o.getCreateBy(), o.getSalesId(), o.getMerchId(), c.getCreateBy());
        return o;
    }

    private OrderChange lock(long id) {
        OrderChange c = changeMapper.lockById(id);
        visible(c);
        return c;
    }

    // ---------- 新建、改、删 ----------

    @Override
    @Transactional
    public DocSaved create(OrderChangeCreateReq req) {
        Order o = orderMapper.lockById(req.getOrderId());
        if (o == null) {
            throw new BizException(404, "订单不存在");
        }
        DataScope.current(false).check(o.getCreateBy(), o.getSalesId(), o.getMerchId());
        List<OrderChangeItem> items = diff(o, req);
        check(o, null, req.isCancel(), req.getResponsibleParty(), items);
        OrderChange c = new OrderChange();
        c.setOrderId(o.getId());
        c.setCompanyId(o.getCompanyId());
        applyForm(c, req, items);
        c.setStatus(DRAFT);
        c.setNo(serialNoGenerator.daily(BizType.ORDER_CHANGE.getPrefix()));
        changeMapper.insert(c);
        insertItems(c.getId(), items);
        attachmentService.replace(BizType.ORDER_CHANGE, c.getId(), Attachment.FILE, req.getFiles());
        docLogService.add(BizType.ORDER_CHANGE, c.getId(), "create", null);
        return new DocSaved(c.getId(), c.getNo(), 0);
    }

    @Override
    @Transactional
    public int update(long id, OrderChangeSaveReq req) {
        OrderChange c = lock(id);
        FLOW.next("edit", c.getStatus());
        if (!c.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        Order o = orderMapper.lockById(c.getOrderId());
        List<OrderChangeItem> items = diff(o, req);
        check(o, id, req.isCancel(), req.getResponsibleParty(), items);
        applyForm(c, req, items);
        UpdateWrapper<OrderChange> update = Updates.replaceById(c)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (changeMapper.update(c, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        changeItemMapper.deleteByChange(id);
        insertItems(id, items);
        docLogService.add(BizType.ORDER_CHANGE, id, "update", null);
        return req.getVersion() + 1;
    }

    private static void applyForm(OrderChange c, OrderChangeForm f, List<OrderChangeItem> items) {
        c.setChangeTypes(typesText(f.isCancel(), items));
        c.setReason(f.getReason().strip());
        c.setResponsibleParty(f.getResponsibleParty());
        c.setFeeNote(OrderTexts.blankToNull(f.getFeeNote()));
    }

    private void insertItems(long changeId, List<OrderChangeItem> items) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setChangeId(changeId);
            items.get(i).setSeq(i + 1);
        }
        if (!items.isEmpty()) {
            changeItemMapper.insert(items);
        }
    }

    @Override
    @Transactional
    public void delete(long id) {
        OrderChange c = lock(id);
        FLOW.next("remove", c.getStatus());
        changeItemMapper.deleteByChange(id);
        attachmentService.deleteByDoc(BizType.ORDER_CHANGE, id);
        docLogService.deleteByDoc(BizType.ORDER_CHANGE, id);
        changeMapper.deleteById(id);
    }

    // ---------- 算改了哪几处、检查 ----------

    /**
     * 表单（改完以后的样子）和订单现在比，一样的不算（docs/api/02 §3.14）。顺序：交期、资料包、单价、件数。
     * 表单格式上的问题（重复、不存在、不是订单里的款）直接 400；业务上能不能改由 check 判断
     */
    List<OrderChangeItem> diff(Order o, OrderChangeForm f) {
        if (f.isCancel()) {
            return List.of();
        }
        List<OrderItem> rows = itemMapper.selectByOrder(o.getId());
        Map<Long, OrderItem> bySku = bySku(rows);
        Set<Long> styles = rows.stream().map(OrderItem::getStyleId).collect(Collectors.toCollection(LinkedHashSet::new));
        List<OrderChangeItem> items = new ArrayList<>();

        if (f.getRequiredDate() != null && !f.getRequiredDate().equals(o.getRequiredDate())) {
            if (f.getRequiredDate().isBefore(o.getOrderDate())) {
                throw new BizException(400, "要求交期不能早于下单日期");
            }
            OrderChangeItem ci = item(ChangeType.DUE, null);
            ci.setFromDate(o.getRequiredDate());
            ci.setToDate(f.getRequiredDate());
            items.add(ci);
        }

        Set<Long> seen = new HashSet<>();
        for (OrderChangeForm.TechPack t : f.getTechPacks()) {
            requireStyle(styles, seen, t.styleId());
            Long locked = lockedPack(rows, t.styleId());
            if (t.techPackId().equals(locked)) {
                continue;
            }
            OrderChangeItem ci = item(ChangeType.TECH_PACK, t.styleId());
            ci.setFromTechPackId(locked);
            ci.setToTechPackId(t.techPackId());
            items.add(ci);
        }

        seen.clear();
        Map<Long, BigDecimal> newPrices = new HashMap<>();
        for (OrderChangeForm.Price p : f.getPrices()) {
            requireStyle(styles, seen, p.styleId());
            BigDecimal to = NumericLimits.price(p.price(), "改价：");
            BigDecimal from = uniformPrice(OrderServiceImpl.active(rowsOf(rows, p.styleId())));
            newPrices.put(p.styleId(), to);
            if (from != null && from.compareTo(to) == 0) {
                continue;
            }
            OrderChangeItem ci = item(ChangeType.PRICE, p.styleId());
            ci.setFromPrice(from);
            ci.setToPrice(to);
            items.add(ci);
        }

        List<Long> newSkus = f.getQuantities().stream().map(OrderChangeForm.Qty::styleSkuId).filter(k -> !bySku.containsKey(k)).toList();
        Map<Long, Long> styleOfNew = newSkus.isEmpty() ? Map.of() : styleService.styleIdsOfSkus(newSkus);
        Map<Long, StyleOrderInfo> infos = null;
        seen.clear();
        for (OrderChangeForm.Qty q : f.getQuantities()) {
            if (!seen.add(q.styleSkuId())) {
                throw new BizException(400, "同一个 SKU 出现了两次，请刷新后再填");
            }
            OrderItem row = bySku.get(q.styleSkuId());
            int from = row == null ? 0 : row.getQty();
            if (q.qty() == from) {
                continue;
            }
            Long styleId = row != null ? row.getStyleId() : styleOfNew.get(q.styleSkuId());
            if (styleId == null) {
                throw new BizException(400, "有 SKU 不存在了（可能刚被删掉），请刷新后再填");
            }
            if (!styles.contains(styleId)) {
                throw new BizException(400, "只能给订单里已有的款加颜色、尺码：新的款另下一张订单");
            }
            OrderChangeItem ci = item(from == 0 || q.qty() == 0 ? ChangeType.COLOR_SIZE : ChangeType.QTY, styleId);
            ci.setOrderItemId(row == null ? null : row.getId());
            ci.setStyleSkuId(q.styleSkuId());
            ci.setFromQty(from);
            ci.setToQty(q.qty());
            if (from == 0) {
                if (infos == null) {
                    infos = styleService.orderInfos(styles);
                }
                ci.setToPrice(newPrices.containsKey(styleId) ? newPrices.get(styleId)
                        : defaultPrice(rows, infos.get(styleId), q.styleSkuId()));
            }
            items.add(ci);
        }
        return items;
    }

    private static OrderChangeItem item(ChangeType type, Long styleId) {
        OrderChangeItem ci = new OrderChangeItem();
        ci.setChangeType(type);
        ci.setStyleId(styleId);
        return ci;
    }

    private static void requireStyle(Set<Long> styles, Set<Long> seen, Long styleId) {
        if (!styles.contains(styleId)) {
            throw new BizException(400, "这个款不在订单里，请刷新后再填");
        }
        if (!seen.add(styleId)) {
            throw new BizException(400, "同一个款发了两次，请刷新后再填");
        }
    }

    /**
     * 新加（或者减到 0 又加回来）的 SKU 用什么单价：款的单价都一样就用它；不一样用同颜色已有的一行；再没有用这个款第一行的
     */
    private static BigDecimal defaultPrice(List<OrderItem> rows, StyleOrderInfo info, long skuId) {
        List<OrderItem> styleRows = OrderServiceImpl.active(rowsOf(rows, info.id()));
        BigDecimal uniform = uniformPrice(styleRows);
        if (uniform != null || styleRows.isEmpty()) {
            return uniform;
        }
        Map<Long, Long> colorOf = info.skus().stream().collect(Collectors.toMap(StyleOrderInfo.Sku::id, StyleOrderInfo.Sku::colorId));
        Long color = colorOf.get(skuId);
        return styleRows.stream().filter(r -> Objects.equals(colorOf.get(r.getStyleSkuId()), color)).findFirst()
                .orElse(styleRows.getFirst()).getPrice();
    }

    /** 这几行的单价都一样（都填了）就是它，不然 null */
    static BigDecimal uniformPrice(List<OrderItem> rows) {
        if (rows.isEmpty() || rows.getFirst().getPrice() == null) {
            return null;
        }
        BigDecimal first = rows.getFirst().getPrice();
        return rows.stream().allMatch(r -> r.getPrice() != null && r.getPrice().compareTo(first) == 0) ? first : null;
    }

    /** 这个款锁定的资料包（审过的订单每行都有，同一个款一样） */
    private static Long lockedPack(List<OrderItem> rows, Long styleId) {
        return rowsOf(rows, styleId).stream().map(OrderItem::getTechPackId).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static List<OrderItem> rowsOf(Collection<OrderItem> rows, Long styleId) {
        return rows.stream().filter(r -> r.getStyleId().equals(styleId)).toList();
    }

    private static Map<Long, OrderItem> bySku(List<OrderItem> rows) {
        return rows.stream().collect(Collectors.toMap(OrderItem::getStyleSkuId, Function.identity()));
    }

    /**
     * 新建、改草稿、提交都跑（docs/api/02 §3.14 的表）。订单状态、另一张没审完的、"没有改动"直接 1000；
     * 件数小于已排产、新加的颜色不能下、资料包不是现在生效的版本，一次列全：1000，消息用"；"连起来，data 是清单。
     * 每处的"原"要还等于订单现在的值（提交时 items 是存着的，这期间订单可能变了），对不上 1002
     */
    void check(Order o, Long selfId, boolean cancel, ResponsibleParty party, List<OrderChangeItem> items) {
        if (!OrderServiceImpl.FLOW.allows("change", o.getStatus())) {
            throw new BizException("订单现在是「" + OrderServiceImpl.ORDER_STATUS.label(o.getStatus()).text() + "」，不能变更");
        }
        OrderChange open = changeMapper.selectOpen(o.getId());
        if (open != null && !open.getId().equals(selfId)) {
            throw new BizException("已经有变更单 " + open.getNo() + " 没审完，先处理它");
        }
        tradeFeature.rejectIfOff(party == ResponsibleParty.CUSTOMER, "责任方「客户」");
        if (cancel) {
            return;
        }
        if (items.isEmpty()) {
            throw new BizException("没有改动");
        }
        List<OrderItem> rows = itemMapper.selectByOrder(o.getId());
        checkValueLimits(rows, items);
        Map<Long, OrderItem> bySku = bySku(rows);
        Set<Long> styles = rows.stream().map(OrderItem::getStyleId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(styles);

        Map<Long, Long> targetPack = new HashMap<>();
        styles.forEach(s -> targetPack.put(s, lockedPack(rows, s)));
        Set<Long> packChanged = new HashSet<>();
        Map<Long, Integer> finalQty = new HashMap<>();
        rows.forEach(r -> finalQty.put(r.getStyleSkuId(), r.getQty()));
        Set<Long> added = new HashSet<>();
        List<String> problems = new ArrayList<>();
        List<OrderChangeItem> packItems = new ArrayList<>();
        for (OrderChangeItem ci : items) {
            switch (ci.getChangeType()) {
                case DUE -> {
                    if (!Objects.equals(o.getRequiredDate(), ci.getFromDate())) {
                        throw stale();
                    }
                }
                case TECH_PACK -> {
                    if (!Objects.equals(lockedPack(rows, ci.getStyleId()), ci.getFromTechPackId())) {
                        throw stale();
                    }
                    targetPack.put(ci.getStyleId(), ci.getToTechPackId());
                    packChanged.add(ci.getStyleId());
                    packItems.add(ci);
                }
                case PRICE -> {
                    BigDecimal now = uniformPrice(OrderServiceImpl.active(rowsOf(rows, ci.getStyleId())));
                    if (ci.getFromPrice() != null && (now == null || now.compareTo(ci.getFromPrice()) != 0)) {
                        throw stale();
                    }
                }
                case QTY, COLOR_SIZE -> {
                    OrderItem row = bySku.get(ci.getStyleSkuId());
                    if ((row == null ? 0 : row.getQty()) != ci.getFromQty()) {
                        throw stale();
                    }
                    if (row != null && ci.getToQty() < row.getScheduledQty()) {
                        problems.add(skuCode(infos, ci) + "：" + OrderServiceImpl.reductionMessage(row, ci.getToQty(),
                                referenceService.referrers(BizType.ORDER, o.getId())));
                    }
                    finalQty.put(ci.getStyleSkuId(), ci.getToQty());
                    if (ci.getFromQty() == 0) {
                        added.add(ci.getStyleSkuId());
                    }
                }
                case CANCEL -> {
                }
            }
        }

        // 换资料包只能换到这个款现在生效的生产 BOM
        Map<Long, PackBrief> current = packItems.isEmpty() ? Map.of()
                : techPackService.effectiveProd(packItems.stream().map(OrderChangeItem::getStyleId).toList());
        for (OrderChangeItem ci : packItems) {
            PackBrief cur = current.get(ci.getStyleId());
            if (cur == null || cur.id() != ci.getToTechPackId()) {
                problems.add(infos.get(ci.getStyleId()).code() + " 只能换到现在生效的版本"
                        + (cur == null ? "：现在没有生效的生产 BOM" : "（" + cur.versionName() + "）"));
            }
        }

        // 新加的 SKU 要能下；换了资料包的款，留下的每个颜色都要在新版本里配了料
        Map<Long, PackBrief> packs = techPackService.briefs(targetPack.values().stream().filter(Objects::nonNull).collect(Collectors.toSet()));
        for (Long styleId : styles) {
            StyleOrderInfo info = infos.get(styleId);
            PackBrief pack = targetPack.get(styleId) == null ? null : packs.get(targetPack.get(styleId));
            Map<Long, StyleOrderInfo.Color> colors = info.colors().stream()
                    .collect(Collectors.toMap(StyleOrderInfo.Color::id, Function.identity()));
            Set<Long> told = new HashSet<>();
            for (StyleOrderInfo.Sku k : info.skus()) {
                boolean isAdded = added.contains(k.id());
                if (finalQty.getOrDefault(k.id(), 0) <= 0 || (!isAdded && !packChanged.contains(styleId))) {
                    continue;
                }
                StyleOrderInfo.Color c = colors.get(k.colorId());
                String colorName = c == null ? k.code() : c.name();
                if (isAdded && !k.enabled()) {
                    problems.add(k.code() + " 已停用");
                } else if (isAdded && (c == null || !c.enabled())) {
                    if (told.add(k.colorId())) {
                        problems.add(info.code() + " " + colorName + "：颜色在款上停用了");
                    }
                } else if (pack != null && !pack.covers(k.colorId()) && told.add(k.colorId())) {
                    problems.add(info.code() + " " + colorName + "：资料包 " + pack.versionName() + " 里没给这个颜色配料"
                            + (packChanged.contains(styleId) ? "" : "，先换资料包版本"));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, String.join("；", problems), problems);
        }
        List<PackRequirement> requirements = new ArrayList<>();
        infos.forEach((styleId, info) -> info.skus().stream().filter(k -> finalQty.getOrDefault(k.id(), 0) > 0)
                .forEach(k -> requirements.add(new PackRequirement(targetPack.get(styleId), k.id()))));
        techPackService.checkLockedRequirements(requirements);
    }

    /** 改完以后每个 SKU 的单价、金额和整单合计放不放得下（提变更时就查，审核写回时再查一次） */
    private static void checkValueLimits(List<OrderItem> rows, List<OrderChangeItem> changes) {
        Map<Long, OrderItem> finalRows = new LinkedHashMap<>();
        for (OrderItem row : rows) {
            OrderItem copy = new OrderItem();
            copy.setStyleId(row.getStyleId());
            copy.setStyleSkuId(row.getStyleSkuId());
            copy.setQty(row.getQty());
            copy.setPrice(row.getPrice());
            finalRows.put(row.getStyleSkuId(), copy);
        }
        for (OrderChangeItem change : changes) {
            if (change.getChangeType() == ChangeType.PRICE) {
                NumericLimits.price(change.getToPrice(), "改价：");
                finalRows.values().stream().filter(r -> Objects.equals(r.getStyleId(), change.getStyleId()))
                        .forEach(r -> r.setPrice(change.getToPrice()));
            }
        }
        for (OrderChangeItem change : changes) {
            if (change.getChangeType() != ChangeType.QTY && change.getChangeType() != ChangeType.COLOR_SIZE) continue;
            OrderItem row = finalRows.computeIfAbsent(change.getStyleSkuId(), id -> {
                OrderItem added = new OrderItem();
                added.setStyleId(change.getStyleId());
                added.setStyleSkuId(id);
                return added;
            });
            row.setQty(change.getToQty());
            if (change.getFromQty() == 0) row.setPrice(change.getToPrice());
        }
        finalRows.values().forEach(r -> r.setAmount(Num.amount(r.getQty(), r.getPrice())));
        OrderServiceImpl.totals(new Order(), new ArrayList<>(finalRows.values()));
    }

    private static String skuCode(Map<Long, StyleOrderInfo> infos, OrderChangeItem ci) {
        StyleOrderInfo info = infos.get(ci.getStyleId());
        return info == null ? "" : info.skus().stream().filter(k -> k.id() == ci.getStyleSkuId()).map(StyleOrderInfo.Sku::code)
                .findFirst().orElse("");
    }

    private static BizException stale() {
        return new BizException(ErrorCodes.STATE_CHANGED, "订单在这期间变了，改一下这张变更单再提");
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        OrderChange c = lock(id);
        switch (action) {
            case "submit" -> submit(c, req);
            case "withdraw" -> {
                if (!isCreator(c.getCreateBy())) {
                    throw new BizException(403, "只有制单人能撤回");
                }
                change(c, "withdraw", DRAFT, req, cas(c, DRAFT, req));
            }
            case "reject" -> {
                String reason = OrderTexts.blankToNull(req.reason());
                if (reason == null) {
                    throw new BizException(400, "请写驳回原因");
                }
                change(c, "reject", DRAFT, req, cas(c, DRAFT, req).set(OrderChange::getRejectReason, reason));
            }
            case "audit" -> audit(c, req);
            case "void" -> change(c, "void", VOIDED, req, cas(c, VOIDED, req));
            default -> throw new BizException(404, "变更单没有动作 " + action);
        }
    }

    /** 比较并交换：状态还是原来的、version 没变，version + 1 */
    private static LambdaUpdateWrapper<OrderChange> cas(OrderChange c, DocStatus to, DocActionReq req) {
        return StatusCas.of(OrderChange::getId, c.getId(), OrderChange::getStatus, c.getStatus(), to)
                .setSql("version = version + 1")
                .eq(OrderChange::getVersion, req.version());
    }

    /** 查转换表、执行 cas、记操作 */
    private void change(OrderChange c, String action, DocStatus to, DocActionReq req, LambdaUpdateWrapper<OrderChange> cas) {
        FLOW.next(action, c.getStatus(), to);
        StatusCas.update(changeMapper, new OrderChange(), cas);
        docLogService.add(BizType.ORDER_CHANGE, c.getId(), action, c.getStatus(), to, OrderTexts.blankToNull(req.reason()));
    }

    /** 提交：再跑一遍检查（这期间订单可能变了）；取消订单还要没有下游单据（1005，data 是清单） */
    private void submit(OrderChange c, DocActionReq req) {
        FLOW.next("submit", c.getStatus());
        if (!c.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        Order o = orderMapper.lockById(c.getOrderId());
        boolean cancel = types(c.getChangeTypes()).contains(ChangeType.CANCEL);
        check(o, c.getId(), cancel, c.getResponsibleParty(), changeItemMapper.selectByChange(c.getId()));
        if (cancel) {
            referenceService.assertNoReferrer(BizType.ORDER, o.getId(), "取消");
        }
        change(c, "submit", PENDING, req, cas(c, PENDING, req).set(OrderChange::getRejectReason, null));
    }

    /** 审核：写回订单（核对"原"、件数不小于已排产，在 applyChange 里），再给下游单据发提醒 */
    private void audit(OrderChange c, DocActionReq req) {
        FLOW.next("audit", c.getStatus());
        if (!c.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张变更单刚被别人审过或改过，请刷新后再审");
        }
        List<OrderChangeItem> items = changeItemMapper.selectByChange(c.getId());
        orderService.applyChange(c, items);
        change(c, "audit", APPROVED, req, cas(c, APPROVED, req)
                .set(OrderChange::getAuditBy, LoginUser.currentUserId())
                .set(OrderChange::getAuditTime, OffsetDateTime.now(clock)));
        notifyDownstream(c, items);
    }

    /** 给每张引用了这张订单的下游单据插一条变更提醒（README §4.5）；取消订单时已经确认过没有下游 */
    private void notifyDownstream(OrderChange c, List<OrderChangeItem> items) {
        List<DocRef> refs = referenceService.referrers(BizType.ORDER, c.getOrderId());
        if (refs.isEmpty()) {
            return;
        }
        ChangeTexts.Names n = changeTexts.names(items);
        String content = "上游订单 " + orderMapper.selectById(c.getOrderId()).getNo() + " 于 " + LocalDate.now(clock).format(MONTH_DAY)
                + " 变更（" + c.getNo() + "）：" + ChangeTexts.summary(false, items.stream().map(ci -> ChangeTexts.text(ci, n, null)).toList());
        for (DocRef d : refs) {
            docNoticeService.add(d.type(), d.id(), BizType.ORDER_CHANGE, c.getId(), content, d.ownerId());
        }
    }
}
