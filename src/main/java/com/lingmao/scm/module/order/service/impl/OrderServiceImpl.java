package com.lingmao.scm.module.order.service.impl;

import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.security.FieldPermissions;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ImportError;
import com.lingmao.scm.common.excel.ImportResult;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.num.Num;
import com.lingmao.scm.common.num.NumericLimits;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.page.PageQuery;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.StatusCount;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.config.TradeFeature;
import com.lingmao.scm.module.basic.entity.Company;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.dto.ApprovalView;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.DocSaved;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.ApprovalService;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocActions;
import com.lingmao.scm.module.biz.service.DocHeaderService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.biz.service.ReferenceService;
import com.lingmao.scm.module.order.dto.OrderAuditReq;
import com.lingmao.scm.module.order.dto.OrderCreateReq;
import com.lingmao.scm.module.order.dto.OrderDetailVO;
import com.lingmao.scm.module.order.dto.OrderForProduction;
import com.lingmao.scm.module.order.dto.OrderForm;
import com.lingmao.scm.module.order.dto.OrderImportHead;
import com.lingmao.scm.module.order.dto.OrderItemReq;
import com.lingmao.scm.module.order.dto.OrderItemVO;
import com.lingmao.scm.module.order.dto.OrderQuery;
import com.lingmao.scm.module.order.dto.OrderRowVO;
import com.lingmao.scm.module.order.dto.OrderSaveReq;
import com.lingmao.scm.module.order.dto.OrderStyleOptionVO;
import com.lingmao.scm.module.order.dto.OrderStyleRef;
import com.lingmao.scm.module.order.dto.OrderStyleVO;
import com.lingmao.scm.module.order.dto.PendingChange;
import com.lingmao.scm.module.order.dto.ProductionProgress;
import com.lingmao.scm.module.order.dto.StyleOrderRowVO;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.Order;
import com.lingmao.scm.module.order.entity.OrderChange;
import com.lingmao.scm.module.order.entity.OrderChangeItem;
import com.lingmao.scm.module.order.entity.OrderItem;
import com.lingmao.scm.module.order.entity.OrderSource;
import com.lingmao.scm.module.order.mapper.OrderChangeItemMapper;
import com.lingmao.scm.module.order.mapper.OrderChangeMapper;
import com.lingmao.scm.module.order.mapper.OrderItemMapper;
import com.lingmao.scm.module.order.mapper.OrderMapper;
import com.lingmao.scm.module.order.service.OrderService;
import com.lingmao.scm.module.style.dto.StyleOrderInfo;
import com.lingmao.scm.module.style.dto.StyleRef;
import com.lingmao.scm.module.style.service.StyleService;
import com.lingmao.scm.module.system.dto.DictOption;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.module.techpack.dto.PackBrief;
import com.lingmao.scm.module.techpack.dto.PackRequirement;
import com.lingmao.scm.module.techpack.service.TechPackService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lingmao.scm.common.doc.DocStatus.APPROVED;
import static com.lingmao.scm.common.doc.DocStatus.CLOSED;
import static com.lingmao.scm.common.doc.DocStatus.DONE;
import static com.lingmao.scm.common.doc.DocStatus.DRAFT;
import static com.lingmao.scm.common.doc.DocStatus.PENDING;
import static com.lingmao.scm.common.doc.DocStatus.RUNNING;
import static com.lingmao.scm.common.doc.DocStatus.VOIDED;

/**
 * 需求订单（docs/api/02 §2.1、§3）。草稿只查格式；提交、每一级审核查款式和资料包（一次列全）；最后一级审过锁定每个款现在生效的生产 BOM。
 * 已审核以后单头和明细只有变更单能改（applyChange），排产、回货由生产那边回写（第 4 步做生产时加）。
 * <p>
 * 多级审批照 ApprovalService 开头的写法：提交 start，审核 pass（中间一级只记承诺交期、版本号 +1），驳回、撤回、作废 cancel
 */
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /**
     * 状态转换表（docs/api/02 §2.1）。schedule、change、mrp 不改状态，写在这里是为了出按钮；
     * cancel 是变更单取消订单（系统做，没有按钮）；audit 中间一级审过还是待审核
     */
    static final StatusFlow<DocStatus> FLOW = StatusFlow.of(DocStatus.class)
            .on("edit", DRAFT).to(DRAFT)
            .on("submit", DRAFT).to(PENDING)
            .on("withdraw", PENDING).to(DRAFT)
            .on("reject", PENDING).to(DRAFT)
            .on("audit", PENDING).to(PENDING, APPROVED)
            .on("schedule", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("change", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("mrp", APPROVED, RUNNING).to(APPROVED, RUNNING)
            .on("unaudit", APPROVED).to(DRAFT)
            .on("close", APPROVED, RUNNING).to(CLOSED)
            .on("reopen", CLOSED).to(APPROVED, RUNNING)
            .on("void", DRAFT, PENDING).to(VOIDED)
            .on("remove", DRAFT).to(DRAFT)
            .on("cancel", APPROVED, RUNNING).to(VOIDED)
            .build();

    static final String PERM = "order:order:";

    /** 和前端 types/order.ts 的 ORDER_STATUS 一样 */
    static final StatusLabels<DocStatus> ORDER_STATUS = StatusLabels.DOC.rename(RUNNING, "生产中").rename(VOIDED, "已取消");

    static final String NO_PROD_PACK = "没有生效的生产 BOM：先在款式的资料包里建好、点生效";

    /** 生产进度能改的订单状态：结案、取消的不动（取消结案时按结案前的状态回去） */
    private static final Set<DocStatus> PRODUCTION_DRIVEN = Set.of(APPROVED, RUNNING, DONE);

    private static final String NOT_FOUND = "订单不存在";
    private static final String STALE = "这张订单已经被别人改过，请刷新后再改";

    private final OrderMapper orderMapper;
    private final OrderItemMapper itemMapper;
    private final OrderChangeMapper changeMapper;
    private final OrderChangeItemMapper changeItemMapper;
    private final OrderExcel excel;
    private final ChangeTexts changeTexts;
    private final StyleService styleService;
    private final TechPackService techPackService;
    private final CompanyService companyService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final TradeFeature tradeFeature;
    private final DocHeaderService docHeaderService;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final ApprovalService approvalService;
    private final ReferenceService referenceService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;
    private final com.lingmao.scm.module.system.service.FileImportService imports;

    // ---------- 查询 ----------

    @Override
    public PageResult<OrderRowVO> page(OrderQuery query) {
        query.setApprovalIds(query.isAuditForMe() ? approvalService.waitingForMe(BizType.ORDER) : List.of());
        IPage<OrderRowVO> page = orderMapper.selectRowPage(query.toPage(), query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine()));
        fillRows(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public Map<String, Long> counts(OrderQuery query) {
        query.setApprovalIds(query.isAuditForMe() ? approvalService.waitingForMe(BizType.ORDER) : List.of());
        return StatusCount.toMap(orderMapper.selectCounts(query, Keywords.codes(query.getKeyword()), DataScope.current(query.isMine())));
    }

    /** 列表行的款式（缩略图 + 款号）、人名、按钮 */
    private void fillRows(List<OrderRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream().map(OrderRowVO::getId).toList();
        Map<Long, List<Long>> styleIds = new HashMap<>();
        itemMapper.selectStyles(ids).forEach(s -> styleIds.computeIfAbsent(s.orderId(), k -> new ArrayList<>()).add(s.styleId()));
        Set<Long> allStyles = styleIds.values().stream().flatMap(List::stream).collect(Collectors.toSet());
        Map<Long, StyleRef> styles = styleService.refs(allStyles);
        Map<Long, AttachmentVO> images = attachmentService.firsts(BizType.STYLE, allStyles, Attachment.IMAGE);
        Set<Long> userIds = new HashSet<>();
        rows.forEach(r -> {
            userIds.add(r.getSalesId());
            userIds.add(r.getMerchId());
            userIds.add(r.getCreateBy());
        });
        userIds.remove(null);
        Map<Long, String> names = userService.nicknames(userIds);
        Set<Long> mine = rows.stream().anyMatch(r -> PENDING.name().equals(r.getStatus()))
                ? new HashSet<>(approvalService.waitingForMe(BizType.ORDER)) : Set.of();
        Set<Long> openChange = changeMapper.selectOpenOrders(ids);
        Set<Long> unscheduled = itemMapper.selectUnscheduledOrders(ids);
        for (OrderRowVO r : rows) {
            r.setStyles(styleIds.getOrDefault(r.getId(), List.of()).stream()
                    .map(sid -> new OrderStyleRef(sid, styles.containsKey(sid) ? styles.get(sid).code() : null, images.get(sid)))
                    .toList());
            r.setSalesName(names.get(r.getSalesId()));
            r.setMerchName(names.get(r.getMerchId()));
            r.setCreateByName(r.getCreateBy() == null ? null : names.get(r.getCreateBy()));
            r.setActions(actions(DocStatus.valueOf(r.getStatus()), new Ctx(isCreator(r.getCreateBy()),
                    new ApprovalView(0, List.of(), null, mine.contains(r.getId())), openChange.contains(r.getId()),
                    unscheduled.contains(r.getId()), false)));
        }
    }

    /**
     * 算按钮要的业务条件：creator 我是制单人（撤回）、approval 审批块（审核、驳回只给当前这一级的人）、openChange 有没审完的变更单
     * （不出提变更、反审核）、unscheduled 还有 SKU 没排满（排单）、referenced 有下游单据（不出反审核；列表不查，当没有）
     */
    record Ctx(boolean creator, ApprovalView approval, boolean openChange, boolean unscheduled, boolean referenced) {
    }

    /** 按钮（docs/api/02 §2.1）。结案、取消结案看 order:order:audit；排单、提变更、算料看对应单据的新建权限 */
    static List<String> actions(DocStatus status, Ctx c) {
        return DocActions.of(FLOW, status, PERM)
                .perm("edit", PERM + "edit")
                .perm("remove", PERM + "remove")
                .perm("close", PERM + "audit")
                .perm("reopen", PERM + "audit")
                .perm("schedule", "production:order:add")
                .perm("change", "order:change:add")
                .perm("mrp", "purchase:mrp:add")
                .when("withdraw", c.creator())
                .when("unaudit", !c.openChange() && !c.referenced())
                .when("change", !c.openChange())
                .when("schedule", c.unscheduled())
                .approval(c.approval())
                .list();
    }

    private static boolean isCreator(Long createBy) {
        return createBy != null && createBy.equals(LoginUser.currentUserId());
    }

    @Override
    public OrderDetailVO detail(long id) {
        Order o = getVisible(id);
        List<OrderItem> items = active(itemMapper.selectByOrder(id));
        List<Long> styleIds = items.stream().map(OrderItem::getStyleId).distinct().toList();
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(styleIds);
        Map<Long, PackBrief> current = techPackService.effectiveProd(styleIds);
        Map<Long, PackBrief> locked = techPackService.briefs(items.stream().map(OrderItem::getTechPackId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        OrderDetailVO vo = docHeaderService.fill(new OrderDetailVO(), BizType.ORDER, o, o.getStatus());
        vo.setCompanyName(companyService.getById(o.getCompanyId()).getShortName());
        vo.setOrderType(o.getOrderType());
        vo.setProductLine(o.getProductLine());
        vo.setCustomerId(o.getCustomerId());
        vo.setCustomerOrderNo(o.getCustomerOrderNo());
        vo.setContractNo(o.getContractNo());
        vo.setBrand(o.getBrand());
        vo.setOrderDate(o.getOrderDate());
        vo.setRequiredDate(o.getRequiredDate());
        vo.setCommitDate(o.getCommitDate());
        Map<Long, String> names = userService.nicknames(List.of(o.getSalesId(), o.getMerchId()));
        vo.setSalesId(o.getSalesId());
        vo.setSalesName(names.get(o.getSalesId()));
        vo.setMerchId(o.getMerchId());
        vo.setMerchName(names.get(o.getMerchId()));
        vo.setCurrency(o.getCurrency());
        vo.setDeliveryReq(o.getDeliveryReq());
        vo.setTotalQty(o.getTotalQty());
        vo.setTotalAmount(o.getTotalAmount());
        vo.setSource(o.getSource());
        vo.setScheduledQty(items.stream().mapToInt(OrderItem::getScheduledQty).sum());
        vo.setReturnedQty(items.stream().mapToInt(OrderItem::getReturnedQty).sum());

        Map<Long, List<OrderItem>> byStyle = byStyle(items);
        List<OrderStyleVO> styles = new ArrayList<>();
        List<OrderItemVO> itemVOs = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> e : byStyle.entrySet()) {
            StyleOrderInfo info = infos.get(e.getKey());
            List<OrderItem> rows = e.getValue();
            Long lockedId = rows.stream().map(OrderItem::getTechPackId).filter(Objects::nonNull).findFirst().orElse(null);
            PackBrief lockedPack = lockedId == null ? null : locked.get(lockedId);
            PackBrief currentPack = current.get(e.getKey());
            OrderStyleVO s = new OrderStyleVO();
            s.setStyleId(e.getKey());
            s.setStyleCode(info.code());
            s.setStyleName(info.name());
            s.setAliasName(info.aliasName());
            s.setImage(info.image());
            s.setSizes(info.sizes());
            s.setTechPackId(lockedId);
            s.setTechPackVersion(lockedPack == null ? null : lockedPack.version());
            s.setCurrentPackId(currentPack == null ? null : currentPack.id());
            s.setCurrentPackVersion(currentPack == null ? null : currentPack.version());
            s.setQty(rows.stream().mapToInt(OrderItem::getQty).sum());
            s.setAmount(sumAmount(rows));
            styles.add(s);
            itemVOs.addAll(itemVOs(info, rows));
        }
        vo.setStyles(styles);
        vo.setItems(itemVOs);

        OrderChange open = changeMapper.selectOpen(id);
        if (open != null) {
            List<OrderChangeItem> changeItems = changeItemMapper.selectByChange(open.getId());
            ChangeTexts.Names n = changeTexts.names(changeItems);
            List<String> texts = changeItems.stream().map(ci -> ChangeTexts.text(ci, n, null)).toList();
            vo.setPendingChange(new PendingChange(open.getId(), open.getNo(), open.getStatus().name(),
                    ChangeTexts.summary(open.getChangeTypes().contains(ChangeType.CANCEL.name()), texts)));
        }
        boolean referenced = o.getStatus() == APPROVED && !referenceService.referrers(BizType.ORDER, id).isEmpty();
        boolean unscheduled = items.stream().anyMatch(i -> i.getScheduledQty() < i.getQty());
        vo.setActions(actions(o.getStatus(), new Ctx(isCreator(o.getCreateBy()), vo.getApproval(), open != null,
                unscheduled, referenced)));
        return vo;
    }

    /** 一个款的明细：按款上颜色的顺序、尺码组的顺序排 */
    private static List<OrderItemVO> itemVOs(StyleOrderInfo info, List<OrderItem> rows) {
        Map<Long, StyleOrderInfo.Sku> skus = info.skus().stream().collect(Collectors.toMap(StyleOrderInfo.Sku::id, Function.identity()));
        Map<Long, StyleOrderInfo.Color> colors = new HashMap<>();
        Map<Long, Integer> colorOrder = new HashMap<>();
        for (int n = 0; n < info.colors().size(); n++) {
            colors.put(info.colors().get(n).id(), info.colors().get(n));
            colorOrder.put(info.colors().get(n).id(), n);
        }
        return rows.stream().map(i -> {
            StyleOrderInfo.Sku k = skus.get(i.getStyleSkuId());
            StyleOrderInfo.Color c = k == null ? null : colors.get(k.colorId());
            OrderItemVO v = new OrderItemVO();
            v.setId(i.getId());
            v.setSeq(i.getSeq());
            v.setStyleId(i.getStyleId());
            v.setStyleSkuId(i.getStyleSkuId());
            v.setSkuCode(k == null ? null : k.code());
            v.setColorId(k == null ? null : k.colorId());
            v.setColorCode(c == null ? null : c.code());
            v.setColorName(c == null ? null : c.name());
            v.setColorHex(c == null ? null : c.hex());
            v.setSizeCode(k == null ? null : k.sizeCode());
            v.setQty(i.getQty());
            v.setPrice(i.getPrice());
            v.setAmount(i.getAmount());
            v.setLineDueDate(i.getLineDueDate());
            v.setTechPackId(i.getTechPackId());
            v.setScheduledQty(i.getScheduledQty());
            v.setReturnedQty(i.getReturnedQty());
            v.setRemark(i.getRemark());
            return v;
        }).sorted(Comparator.comparing((OrderItemVO v) -> colorOrder.getOrDefault(v.getColorId(), Integer.MAX_VALUE))
                .thenComparing(v -> sizeIndex(info.sizes(), v.getSizeCode()))
                .thenComparing(OrderItemVO::getSeq)).toList();
    }

    private static int sizeIndex(List<String> sizes, String size) {
        int i = sizes.indexOf(size);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** 件数 > 0 的行：0 是变更单减掉的，页面上不出 */
    static List<OrderItem> active(List<OrderItem> items) {
        return items.stream().filter(i -> i.getQty() > 0).toList();
    }

    /** 按款分组，款的顺序按第一次出现的行号 */
    static Map<Long, List<OrderItem>> byStyle(List<OrderItem> items) {
        return items.stream().sorted(Comparator.comparing(OrderItem::getSeq))
                .collect(Collectors.groupingBy(OrderItem::getStyleId, LinkedHashMap::new, Collectors.toList()));
    }

    private static BigDecimal sumAmount(List<OrderItem> items) {
        return Num.money(items.stream().map(OrderItem::getAmount).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Override
    public List<OrderStyleOptionVO> styleOptions(List<Long> styleIds) {
        Map<Long, StyleOrderInfo> infos = styleService.orderInfos(styleIds);
        Map<Long, PackBrief> packs = techPackService.effectiveProd(infos.keySet());
        return styleIds.stream().distinct().map(infos::get).filter(Objects::nonNull)
                .map(info -> option(info, packs.get(info.id()))).toList();
    }

    static OrderStyleOptionVO option(StyleOrderInfo info, PackBrief pack) {
        List<OrderStyleOptionVO.Color> colors = info.colors().stream().map(c -> {
            String reason = colorProblem(info, c, pack);
            return new OrderStyleOptionVO.Color(c.id(), c.code(), c.name(), c.hex(), reason == null, reason);
        }).toList();
        List<OrderStyleOptionVO.Sku> skus = info.skus().stream()
                .map(k -> new OrderStyleOptionVO.Sku(k.id(), k.colorId(), k.sizeCode(), k.code(), k.enabled() ? 1 : 0)).toList();
        return new OrderStyleOptionVO(info.id(), info.code(), info.name(), info.aliasName(), info.image(), info.status(),
                info.techPackStatus(), pack == null ? null : pack.id(), pack == null ? null : pack.version(), info.sizes(),
                colors, skus, info.tagPrice(), styleProblem(info, pack));
    }

    /** 款不能下单的原因（docs/api/02 §3.4）；能下是 null */
    static String styleProblem(StyleOrderInfo info, PackBrief pack) {
        return switch (info.status()) {
            case DRAFT -> "草稿款不能下单：先在款式里启用";
            case DISABLED -> "已停用";
            case ENABLED -> pack == null ? NO_PROD_PACK : null;
        };
    }

    /** 颜色不能下的原因；还没有生效的生产 BOM（pack 是 null）时不看配料，款上已经标了 */
    static String colorProblem(StyleOrderInfo info, StyleOrderInfo.Color c, PackBrief pack) {
        if (!c.enabled()) {
            return "颜色在款上停用了";
        }
        if (info.skus().stream().noneMatch(k -> k.colorId() == c.id() && k.enabled())) {
            return "这个颜色的 SKU 都停用了";
        }
        if (pack != null && !pack.covers(c.id())) {
            return "资料包 " + pack.versionName() + " 里没给这个颜色配料";
        }
        return null;
    }

    /**
     * 提交、每一级审核都跑的检查（docs/api/02 §3.6）：每个款启用、有生效的生产 BOM；每个 SKU 启用，颜色在款上没停用、在生产 BOM 里配了料。
     * 一条一句话，同一个颜色只说一次；都没问题是空的
     */
    static List<String> packProblems(List<OrderItem> items, Map<Long, StyleOrderInfo> infos, Map<Long, PackBrief> packs) {
        if (items.isEmpty()) {
            return List.of("明细一行都没有：先加款式、填件数");
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItem>> e : byStyle(items).entrySet()) {
            StyleOrderInfo info = infos.get(e.getKey());
            PackBrief pack = packs.get(e.getKey());
            String styleProblem = styleProblem(info, pack);
            if (styleProblem != null) {
                problems.add(info.code() + " " + styleProblem);
                continue;
            }
            Map<Long, StyleOrderInfo.Sku> skus = info.skus().stream().collect(Collectors.toMap(StyleOrderInfo.Sku::id, Function.identity()));
            Map<Long, StyleOrderInfo.Color> colors = info.colors().stream()
                    .collect(Collectors.toMap(StyleOrderInfo.Color::id, Function.identity()));
            Set<Long> told = new HashSet<>();
            for (OrderItem i : e.getValue()) {
                StyleOrderInfo.Sku k = skus.get(i.getStyleSkuId());
                if (!k.enabled()) {
                    problems.add(k.code() + " 已停用");
                    continue;
                }
                StyleOrderInfo.Color c = colors.get(k.colorId());
                String colorName = c == null ? k.code() : c.name();
                if ((c == null || !c.enabled()) && told.add(k.colorId())) {
                    problems.add(info.code() + " " + colorName + "：颜色在款上停用了");
                } else if (!pack.covers(k.colorId()) && told.add(k.colorId())) {
                    problems.add(info.code() + " " + colorName + "：资料包 " + pack.versionName() + " 里没给这个颜色配料");
                }
            }
        }
        return problems;
    }

    @Override
    public PageResult<StyleOrderRowVO> byStyle(long styleId, PageQuery page) {
        IPage<StyleOrderRowVO> result = orderMapper.selectStylePage(page.toPage(), styleId, DataScope.current(false));
        Map<Long, String> names = userService.nicknames(result.getRecords().stream().map(StyleOrderRowVO::getMerchId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        result.getRecords().forEach(r -> r.setMerchName(names.get(r.getMerchId())));
        return PageResult.of(result);
    }

    @Override
    public void checkVisible(long id) {
        getVisible(id);
    }

    private Order getVisible(long id) {
        return visible(orderMapper.selectById(id));
    }

    /** 改、删、动作之前锁住单头：和审核、变更写回排队，后到的看到的是改完的状态 */
    private Order lock(long id) {
        return visible(orderMapper.lockById(id));
    }

    /**
     * 数据范围（PRD §3.2）：制单人、业务员、跟单员。另外待审核时轮到审的人也能看：规则加的那一级审批人不一定在范围里，
     * 不然打不开要他审的单
     */
    private Order visible(Order o) {
        if (o == null || !LineScope.current().canSee(o.getProductLine())) {
            throw new BizException(404, NOT_FOUND);
        }
        if (!DataScope.current(false).canSee(o.getCreateBy(), o.getSalesId(), o.getMerchId())
                && !(o.getStatus() == PENDING && isMyTurn(o.getId()))) {
            throw new BizException(404, NOT_FOUND);
        }
        com.lingmao.scm.common.scope.CompanyContext.target(o.getCompanyId());
        return o;
    }

    private boolean isMyTurn(long id) {
        ApprovalView view = approvalService.view(BizType.ORDER, id);
        return view != null && view.canPass();
    }

    // ---------- 新建、改、删 ----------

    @Override
    @Transactional
    public DocSaved create(OrderCreateReq req) {
        com.lingmao.scm.common.scope.CompanyContext.create(req.getCompanyId());
        Order o = new Order();
        applyForm(o, req, null);
        o.setSource(OrderSource.MANUAL);
        o.setStatus(DRAFT);
        List<OrderItem> items = buildItems(req.getItems(), new HashMap<>());
        insert(o, items);
        attachmentService.replace(BizType.ORDER, o.getId(), Attachment.FILE, req.getFiles());
        docLogService.add(BizType.ORDER, o.getId(), "create", null);
        return new DocSaved(o.getId(), o.getNo(), 0);
    }

    /** 取单号、算合计、插单头和明细（新建、导入共用） */
    private void insert(Order o, List<OrderItem> items) {
        o.setProductLine(lineOf(items, null));
        totals(o, items);
        o.setNo(serialNoGenerator.daily(BizType.ORDER.getPrefix()));
        orderMapper.insert(o);
        items.forEach(i -> i.setOrderId(o.getId()));
        if (!items.isEmpty()) {
            itemMapper.insert(items);
        }
    }

    @Override
    @Transactional
    public int update(long id, OrderSaveReq req) {
        Order o = lock(id);
        com.lingmao.scm.common.scope.CompanyContext.save(o.getCompanyId(), req.getCompanyId(), req.getExpectedCompanyId());
        FLOW.next("edit", o.getStatus());
        if (!o.getVersion().equals(req.getVersion())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        Order old = new Order();
        BeanUtils.copyProperties(o, old);
        applyForm(o, req, old);

        List<OrderItem> all = itemMapper.selectByOrder(id);
        Map<Long, OrderItem> existing = all.stream().collect(Collectors.toMap(OrderItem::getStyleSkuId, Function.identity()));
        List<OrderItem> items = buildItems(req.getItems(), existing);
        o.setProductLine(lineOf(items, old.getProductLine()));
        // 这次去掉的行：变更明细指着的（审核过、变更过、又反审核回来的）留着、件数记 0，别的删掉
        List<OrderItem> dropped = new ArrayList<>(existing.values());
        Set<Long> referenced = dropped.isEmpty() ? Set.of()
                : changeItemMapper.selectReferencedItems(dropped.stream().map(OrderItem::getId).toList());
        totals(o, items);

        UpdateWrapper<Order> update = Updates.replaceById(o)
                .eq("status", DRAFT.name())
                .eq("version", req.getVersion())
                .setSql("version = version + 1");
        if (orderMapper.update(o, update) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        List<Long> deleteIds = dropped.stream().map(OrderItem::getId).filter(i -> !referenced.contains(i)).toList();
        if (!deleteIds.isEmpty()) {
            itemMapper.deleteByIds(deleteIds);
        }
        int seq = items.size();
        for (OrderItem kept : dropped.stream().filter(i -> referenced.contains(i.getId())).toList()) {
            kept.setSeq(++seq);
            kept.setQty(0);
            kept.setAmount(Num.amount(0, kept.getPrice()));
            itemMapper.update(kept, Updates.replaceById(kept));
        }
        for (OrderItem item : items) {
            item.setOrderId(id);
            if (item.getId() == null) {
                itemMapper.insert(item);
            } else {
                itemMapper.update(item, Updates.replaceById(item));
            }
        }
        docLogService.add(BizType.ORDER, id, "update", null);
        return req.getVersion() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        Order o = lock(id);
        FLOW.next("remove", o.getStatus());
        if (changeMapper.orderHasChanges(id)) {
            throw new BizException("这张订单审核后提过变更单，不能删除，只能作废");
        }
        itemMapper.deleteByOrder(id);
        attachmentService.deleteByDoc(BizType.ORDER, id);
        docLogService.deleteByDoc(BizType.ORDER, id);
        orderMapper.deleteById(id);
    }

    /**
     * 单头（新建、保存草稿、导入共用，docs/api/02 §3.5）。old 是保存前的（新建传 null）：没改的主体、字典值、人后来停用了照样留着，
     * 新选的要启用。TRADE 关时不收客户、客户订单号
     */
    private void applyForm(Order o, OrderForm f, Order old) {
        if (old == null || !Objects.equals(old.getCompanyId(), f.getCompanyId())) {
            checkCompany(f.getCompanyId());
        }
        String orderType = f.getOrderType().strip();
        String brand = OrderTexts.blankToNull(f.getBrand());
        String currency = f.getCurrency().strip();
        checkDict("order_type", orderType, old == null ? null : old.getOrderType(), "订单类型");
        checkDict("brand", brand, old == null ? null : old.getBrand(), "品牌");
        checkDict("currency", currency, old == null ? null : old.getCurrency(), "币种");
        String customerOrderNo = OrderTexts.blankToNull(f.getCustomerOrderNo());
        tradeFeature.rejectIfOff(f.getCustomerId() != null || customerOrderNo != null, "客户、客户订单号");
        if (f.getRequiredDate().isBefore(f.getOrderDate())) {
            throw new BizException(400, "要求交期不能早于下单日期");
        }
        checkUser(f.getSalesId(), old == null ? null : old.getSalesId(), "业务员");
        checkUser(f.getMerchId(), old == null ? null : old.getMerchId(), "跟单员");
        o.setCompanyId(f.getCompanyId());
        o.setOrderType(orderType);
        o.setCustomerId(f.getCustomerId());
        o.setCustomerOrderNo(customerOrderNo);
        o.setContractNo(OrderTexts.blankToNull(f.getContractNo()));
        o.setBrand(brand);
        o.setOrderDate(f.getOrderDate());
        o.setRequiredDate(f.getRequiredDate());
        o.setSalesId(f.getSalesId());
        o.setMerchId(f.getMerchId());
        o.setCurrency(currency);
        o.setDeliveryReq(OrderTexts.blankToNull(f.getDeliveryReq()));
        o.setRemark(OrderTexts.blankToNull(f.getRemark()));
    }

    private void checkCompany(long companyId) {
        Company company = companyService.getById(companyId);
        if (!Integer.valueOf(1).equals(company.getStatus())) {
            throw new BizException("主体" + company.getShortName() + "已停用，请换一个");
        }
    }

    /** 字典值：没改的不查；新填的要在字典里、启用（TRADE 关时"客户订单"不在里面） */
    private void checkDict(String type, String value, String old, String label) {
        if (value == null || value.equals(old)) {
            return;
        }
        DictOption option = dictDataService.listByType(type).stream().filter(d -> d.value().equals(value)).findFirst().orElse(null);
        if (option == null || !option.enabled()) {
            throw new BizException(400, label + "「" + value + "」不在字典里或已停用");
        }
    }

    private void checkUser(Long userId, Long old, String label) {
        if (!userId.equals(old) && !userService.isEnabled(userId)) {
            throw new BizException(400, label + "的账号不存在或已停用，请换一个人");
        }
    }

    /**
     * 明细（docs/api/02 §3.5）：一个 SKU 一行（重复 400），SKU 要存在，款式跟着 SKU 走；金额 = 件数 × 单价。
     * existing 是这张单原来的行（按 SKU）：同一个 SKU 沿用原来那一行（id 不变，变更明细可能指着它），用掉的从 existing 里拿走，
     * 剩下的就是这次去掉的
     */
    private List<OrderItem> buildItems(List<OrderItemReq> reqs, Map<Long, OrderItem> existing) {
        Map<Long, Long> styleOf = styleService.styleIdsOfSkus(reqs.stream().map(OrderItemReq::getStyleSkuId).toList());
        Set<Long> seen = new HashSet<>();
        List<OrderItem> items = new ArrayList<>();
        for (OrderItemReq r : reqs) {
            if (!seen.add(r.getStyleSkuId())) {
                throw new BizException(400, "同一个 SKU 出现了两次，请刷新后再填");
            }
            Long styleId = styleOf.get(r.getStyleSkuId());
            if (styleId == null) {
                throw new BizException(400, "有 SKU 不存在了（可能刚被删掉），请刷新后再填");
            }
            OrderItem item = existing.remove(r.getStyleSkuId());
            if (item == null) {
                item = new OrderItem();
            }
            item.setSeq(items.size() + 1);
            item.setStyleId(styleId);
            item.setStyleSkuId(r.getStyleSkuId());
            item.setQty(r.getQty());
            item.setPrice(NumericLimits.price(com.lingmao.scm.security.FieldPermissions.price(
                    com.lingmao.scm.security.FieldPermissions.ORDER, item.getPrice(), r.getPrice()), "第 " + item.getSeq() + " 行："));
            item.setAmount(Num.amount(r.getQty(), item.getPrice()));
            item.setLineDueDate(r.getLineDueDate());
            item.setRemark(OrderTexts.blankToNull(r.getRemark()));
            items.add(item);
        }
        return items;
    }

    /**
     * 订单的品类线（RFC 013 §2）：明细里款式的品类线，只能一条。没有明细的草稿沿用原来的，新建的取我的第一条线。
     * 款式不在我的线里当 SKU 不存在（选款弹窗里本来就列不出来）
     */
    private String lineOf(List<OrderItem> items, String old) {
        Map<Long, String> byStyle = styleService.productLines(items.stream().map(OrderItem::getStyleId).toList());
        Set<String> lines = new LinkedHashSet<>(byStyle.values());
        if (lines.size() > 1) {
            Map<String, String> labels = dictDataService.labels(LineScope.DICT_TYPE);
            throw new BizException("一张订单只能放一条品类线的款，这张里有"
                    + lines.stream().map(l -> labels.getOrDefault(l, l)).collect(Collectors.joining("、")) + "的：分开下单");
        }
        LineScope scope = LineScope.current();
        String line = lines.isEmpty() ? (old != null ? old : scope.first()) : lines.iterator().next();
        if (!scope.canSee(line)) {
            throw new BizException(400, "有 SKU 不存在了（可能刚被删掉），请刷新后再填");
        }
        return line;
    }

    /** 合计件数、金额（没单价的行不算金额） */
    static void totals(Order o, List<OrderItem> items) {
        for (int n = 0; n < items.size(); n++) {
            OrderItem item = items.get(n);
            String label = "第 " + (n + 1) + " 行：";
            NumericLimits.price(item.getPrice(), label);
            NumericLimits.amount(item.getAmount(), label);
        }
        o.setTotalQty(NumericLimits.pieces(items.stream().mapToLong(OrderItem::getQty).sum(), "订单"));
        o.setTotalAmount(NumericLimits.amount(sumAmount(items), "订单合计"));
    }

    // ---------- 状态动作 ----------

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        Order o = lock(id);
        switch (action) {
            case "submit" -> submit(o, req);
            case "withdraw" -> {
                if (!isCreator(o.getCreateBy())) {
                    throw new BizException(403, "只有制单人能撤回");
                }
                change(o, "withdraw", DRAFT, req);
                approvalService.cancel(BizType.ORDER, id);
            }
            case "reject" -> {
                if (OrderTexts.blankToNull(req.reason()) == null) {
                    throw new BizException(400, "请写驳回原因");
                }
                FLOW.next("reject", o.getStatus());
                ApprovalView view = approvalService.view(BizType.ORDER, id);
                if (view == null || !view.canPass()) {
                    throw new BizException(403, view == null || view.waitingFor() == null ? "现在不是你审"
                            : "现在等" + view.waitingFor() + "审");
                }
                change(o, "reject", DRAFT, req);
                approvalService.cancel(BizType.ORDER, id);
            }
            case "unaudit" -> unaudit(o, req);
            case "close" -> change(o, "close", CLOSED, req);
            case "reopen" -> change(o, "reopen", reopenTo(id), req);
            case "void" -> {
                change(o, "void", VOIDED, req);
                approvalService.cancel(BizType.ORDER, id);
            }
            default -> throw new BizException(404, "需求订单没有动作 " + action);
        }
    }

    /** 一次状态转换：查转换表、比较并交换、版本号 +1、操作记录 */
    private void change(Order o, String action, DocStatus to, DocActionReq req) {
        FLOW.next(action, o.getStatus(), to);
        StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, o.getId(), Order::getStatus, o.getStatus(), to)
                .setSql("version = version + 1")
                .eq(Order::getVersion, req.version()));
        docLogService.add(BizType.ORDER, o.getId(), action, o.getStatus(), to, OrderTexts.blankToNull(req.reason()));
    }

    /** 取消结案回到结案前的状态（最近一次结案记下的原状态），找不到按已审核 */
    private DocStatus reopenTo(long id) {
        return docLogService.list(BizType.ORDER, id).stream()
                .filter(l -> "close".equals(l.action()) && l.fromStatus() != null)
                .map(DocLogVO::fromStatus)
                .findFirst()
                .map(DocStatus::valueOf)
                .orElse(APPROVED);
    }

    private void submit(Order o, DocActionReq req) {
        FLOW.next("submit", o.getStatus());
        if (!o.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        List<OrderItem> items = active(itemMapper.selectByOrder(o.getId()));
        checkPacks(items, "提交");
        change(o, "submit", PENDING, req);
        approvalService.start(BizType.ORDER, o.getId(), o.getTotalAmount());
    }

    /** 款式、资料包的检查没过 → 1000，data 是一条一句话的清单；过了返回每个款现在生效的生产 BOM */
    private Map<Long, PackBrief> checkPacks(List<OrderItem> items, String action) {
        List<Long> styleIds = items.stream().map(OrderItem::getStyleId).distinct().toList();
        Map<Long, PackBrief> packs = techPackService.effectiveProd(styleIds);
        List<String> problems = packProblems(items, styleService.orderInfos(styleIds), packs);
        if (!problems.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR, action + "不了：有 " + problems.size() + " 处要先处理", problems);
        }
        techPackService.checkRequirements(items.stream()
                .map(i -> new PackRequirement(packs.get(i.getStyleId()).id(), i.getStyleSkuId())).toList());
        return packs;
    }

    @Override
    public void checkSourceVisible(Collection<Long> sourceIds) {
        Set<Long> ids = new HashSet<>(sourceIds);
        if (ids.isEmpty()) return;
        List<Order> sources = orderMapper.selectByIds(ids);
        if (sources.size() != ids.size()) throw new BizException(404, "来源订单不存在");
        DataScope scope = DataScope.current(false);
        for (Order source : sources) {
            scope.getLines().check(source.getProductLine());
            scope.check(source.getCreateBy(), source.getSalesId(), source.getMerchId());
            com.lingmao.scm.common.scope.CompanyContext.target(source.getCompanyId());
        }
    }

    @Override
    public String checkForMrp(Collection<Long> orderIds) {
        Set<Long> ids = new HashSet<>(orderIds);
        if (ids.isEmpty()) return null;
        List<Order> orders = orderMapper.selectByIds(ids);
        if (orders.size() != ids.size()) throw new BizException(404, "有需求订单不存在，请重新选择");
        DataScope scope = DataScope.current(false);
        for (Order order : orders) {
            scope.getLines().check(order.getProductLine());
            scope.check(order.getCreateBy(), order.getSalesId(), order.getMerchId());
            com.lingmao.scm.common.scope.CompanyContext.target(order.getCompanyId());
        }
        if (orders.stream().map(Order::getCompanyId).distinct().count() > 1)
            throw new BizException(400, "不同主体的订单请分别算料");
        // 品类线（RFC 013 §2）：一张算料单只算一条线，生成的采购单跟着它
        if (orders.stream().map(Order::getProductLine).distinct().count() > 1)
            throw new BizException(400, "不同品类线的订单请分别算料");
        List<String> problems = orders.stream().filter(o -> o.getStatus() != APPROVED && o.getStatus() != RUNNING)
                .map(o -> "订单 " + o.getNo() + " 只能在已审核或生产中状态算料").toList();
        if (!problems.isEmpty()) throw new BizException(ErrorCodes.BIZ_ERROR, problems.getFirst(), problems);
        return orders.getFirst().getProductLine();
    }

    @Override
    @Transactional
    public void audit(long id, OrderAuditReq req) {
        Order o = lock(id);
        FLOW.next("audit", o.getStatus(), APPROVED);
        if (!o.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这张订单刚被别人审过或改过，请刷新后再审");
        }
        if (req.commitDate().isBefore(o.getOrderDate())) {
            throw new BizException(400, "承诺交期不能早于下单日期");
        }
        List<OrderItem> items = active(itemMapper.selectByOrder(id));
        Map<Long, PackBrief> packs = checkPacks(items, "审核");
        if (!approvalService.pass(BizType.ORDER, id)) {
            // 中间一级：还是待审核，承诺交期写进单头当下一级的默认值
            StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, id, Order::getStatus, PENDING, PENDING)
                    .set(Order::getCommitDate, req.commitDate())
                    .setSql("version = version + 1")
                    .eq(Order::getVersion, req.version()));
            docLogService.add(BizType.ORDER, id, "audit", "这一级审过了，等下一级审；承诺交期 " + req.commitDate());
            return;
        }
        StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, id, Order::getStatus, PENDING, APPROVED)
                .set(Order::getCommitDate, req.commitDate())
                .set(Order::getAuditBy, LoginUser.currentUserId())
                .set(Order::getAuditTime, OffsetDateTime.now(clock))
                .setSql("version = version + 1")
                .eq(Order::getVersion, req.version()));
        List<String> lockedTexts = new ArrayList<>();
        Map<Long, StyleRef> styles = styleService.refs(packs.keySet());
        for (Long styleId : byStyle(items).keySet()) {
            PackBrief pack = packs.get(styleId);
            itemMapper.update(null, new LambdaUpdateWrapper<OrderItem>()
                    .set(OrderItem::getTechPackId, pack.id())
                    .eq(OrderItem::getOrderId, id)
                    .eq(OrderItem::getStyleId, styleId));
            lockedTexts.add(styles.get(styleId).code() + " " + pack.versionName());
        }
        docLogService.add(BizType.ORDER, id, "audit", PENDING, APPROVED,
                "承诺交期 " + req.commitDate() + "；锁定资料包 " + String.join("、", lockedTexts));
    }

    /** 反审核：没有下游单据、没有没审完的变更单；清掉锁定的资料包（承诺交期留着当下次审核的默认值） */
    private void unaudit(Order o, DocActionReq req) {
        FLOW.next("unaudit", o.getStatus());
        if (!o.getVersion().equals(req.version())) {
            throw new BizException(ErrorCodes.STATE_CHANGED, STALE);
        }
        referenceService.assertNoReferrer(BizType.ORDER, o.getId(), "反审核");
        OrderChange open = changeMapper.selectOpen(o.getId());
        if (open != null) {
            throw new BizException("变更单 " + open.getNo() + " 还没审完，先处理它再反审核");
        }
        StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, o.getId(), Order::getStatus, APPROVED, DRAFT)
                .set(Order::getAuditBy, null)
                .set(Order::getAuditTime, null)
                .setSql("version = version + 1")
                .eq(Order::getVersion, req.version()));
        itemMapper.update(null, new LambdaUpdateWrapper<OrderItem>()
                .set(OrderItem::getTechPackId, null)
                .eq(OrderItem::getOrderId, o.getId()));
        docLogService.add(BizType.ORDER, o.getId(), "unaudit", APPROVED, DRAFT, OrderTexts.blankToNull(req.reason()));
    }

    // ---------- 变更单写回 ----------

    @Override
    @Transactional
    public void applyChange(OrderChange change, List<OrderChangeItem> items) {
        Order o = orderMapper.lockById(change.getOrderId());
        if (o == null) {
            throw new BizException(404, NOT_FOUND);
        }
        if (!FLOW.allows("change", o.getStatus())) {
            throw new BizException("订单现在是「" + ORDER_STATUS.label(o.getStatus()).text() + "」，不能再变更：作废这张变更单");
        }
        String source = "变更单 " + change.getNo();
        if (change.getChangeTypes().contains(ChangeType.CANCEL.name())) {
            referenceService.assertNoReferrer(BizType.ORDER, o.getId(), "取消");
            StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, o.getId(), Order::getStatus, o.getStatus(), VOIDED)
                    .setSql("version = version + 1"));
            docLogService.add(BizType.ORDER, o.getId(), "cancel", o.getStatus(), VOIDED, source + "：" + change.getReason());
            return;
        }
        List<OrderItem> rows = new ArrayList<>(itemMapper.selectByOrder(o.getId()));
        Map<Long, OrderItem> bySku = rows.stream().collect(Collectors.toMap(OrderItem::getStyleSkuId, Function.identity()));
        // 按对象认行（OrderItem 是 @Data，字段一改 hashCode 就变）
        Set<OrderItem> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<OrderItem> added = Collections.newSetFromMap(new IdentityHashMap<>());
        // 先换资料包、改单价，新加的 SKU 再跟着这个款的新版本走
        for (OrderChangeItem ci : items) {
            switch (ci.getChangeType()) {
                case TECH_PACK -> rowsOf(rows, ci.getStyleId()).forEach(r -> {
                    if (!Objects.equals(r.getTechPackId(), ci.getFromTechPackId())) {
                        throw stale();
                    }
                    r.setTechPackId(ci.getToTechPackId());
                    touched.add(r);
                });
                case PRICE -> {
                    List<OrderItem> styleRows = rowsOf(rows, ci.getStyleId());
                    if (ci.getFromPrice() != null && active(styleRows).stream()
                            .anyMatch(r -> r.getPrice() == null || r.getPrice().compareTo(ci.getFromPrice()) != 0)) {
                        throw stale();
                    }
                    styleRows.forEach(r -> {
                        r.setPrice(ci.getToPrice());
                        r.setAmount(Num.amount(r.getQty(), r.getPrice()));
                        touched.add(r);
                    });
                }
                case DUE -> {
                    if (!Objects.equals(o.getRequiredDate(), ci.getFromDate())) {
                        throw stale();
                    }
                    o.setRequiredDate(ci.getToDate());
                }
                default -> {
                }
            }
        }
        for (OrderChangeItem ci : items) {
            if (ci.getChangeType() != ChangeType.QTY && ci.getChangeType() != ChangeType.COLOR_SIZE) {
                continue;
            }
            OrderItem row = bySku.get(ci.getStyleSkuId());
            int current = row == null ? 0 : row.getQty();
            if (current != ci.getFromQty()) {
                throw stale();
            }
            if (row != null && ci.getToQty() < row.getScheduledQty()) {
                String sku = skuCode(row);
                throw new BizException((sku.isEmpty() ? "" : sku + "：") + reductionMessage(row, ci.getToQty(),
                        referenceService.referrers(BizType.ORDER, o.getId())));
            }
            if (row == null) {
                OrderItem sample = rowsOf(rows, ci.getStyleId()).stream().findFirst().orElseThrow(OrderServiceImpl::stale);
                row = new OrderItem();
                row.setOrderId(o.getId());
                row.setStyleId(ci.getStyleId());
                row.setStyleSkuId(ci.getStyleSkuId());
                row.setLineDueDate(sample.getLineDueDate());
                row.setTechPackId(sample.getTechPackId());
                row.setPrice(ci.getToPrice());
                rows.add(row);
                bySku.put(ci.getStyleSkuId(), row);
                added.add(row);
            } else if (ci.getFromQty() == 0 && ci.getToPrice() != null) {
                row.setPrice(ci.getToPrice());   // 减到 0 又加回来的行：用变更单定的单价，不用当初的
            }
            row.setQty(ci.getToQty());
            row.setAmount(Num.amount(row.getQty(), row.getPrice()));
            touched.add(row);
        }
        totals(o, rows); // 整单容量先验证，之后才写任一行
        Set<Long> changedStyles = items.stream().filter(i -> i.getChangeType() == ChangeType.TECH_PACK)
                .map(OrderChangeItem::getStyleId).collect(Collectors.toSet());
        Map<Long, PackBrief> latest = changedStyles.isEmpty() ? Map.of() : techPackService.effectiveProd(changedStyles);
        for (OrderItem row : active(rows)) {
            if (changedStyles.contains(row.getStyleId()) && (latest.get(row.getStyleId()) == null
                    || !Objects.equals(latest.get(row.getStyleId()).id(), row.getTechPackId()))) {
                throw new BizException(ErrorCodes.STATE_CHANGED, "目标生产 BOM 已更新，请重新确认变更版本");
            }
        }
        techPackService.checkLockedRequirements(active(rows).stream()
                .map(row -> new PackRequirement(row.getTechPackId(), row.getStyleSkuId())).toList());
        int seq = rows.stream().filter(r -> r.getSeq() != null).mapToInt(OrderItem::getSeq).max().orElse(0);
        for (OrderItem r : added) {
            r.setSeq(++seq);
            itemMapper.insert(r);   // NEVER 只管更新，插入照写 techPackId；已排产、已回货是 null，库里默认 0
        }
        for (OrderItem r : touched) {
            if (added.contains(r)) {
                continue;
            }
            itemMapper.update(null, new LambdaUpdateWrapper<OrderItem>()
                    .set(OrderItem::getQty, r.getQty())
                    .set(OrderItem::getPrice, r.getPrice())
                    .set(OrderItem::getAmount, r.getAmount())
                    .set(OrderItem::getTechPackId, r.getTechPackId())
                    .eq(OrderItem::getId, r.getId()));
        }
        orderMapper.update(null, new LambdaUpdateWrapper<Order>()
                .set(Order::getRequiredDate, o.getRequiredDate())
                .set(Order::getTotalQty, o.getTotalQty())
                .set(Order::getTotalAmount, o.getTotalAmount())
                .setSql("version = version + 1")
                .eq(Order::getId, o.getId()));
        ChangeTexts.Names n = changeTexts.names(items);
        List<String> texts = items.stream().map(ci -> ChangeTexts.text(ci, n, null)).toList();
        docLogService.add(BizType.ORDER, o.getId(), "change", source + "：" + ChangeTexts.summary(false, texts));
    }

    /**
     * 减到已排产以下的提示（前面由调用方加 SKU 码）：列出这张订单的生产单和状态，说清两种处理办法。
     * 没开始做的撤回、反审核后作废重排；已经领料、报工、回货的不能减，结案后另下新单（结案不退已排量）
     */
    static String reductionMessage(OrderItem row, int target, List<DocRef> refs) {
        String production = refs.stream().filter(r -> r.type() == BizType.PRODUCTION_ORDER)
                .map(r -> r.no() + "（" + r.statusLabel() + "）").collect(Collectors.joining("、"));
        return "已排 " + row.getScheduledQty() + " 件、已回 " + Objects.requireNonNullElse(row.getReturnedQty(), 0)
                + " 件，不能改成 " + target + " 件。先处理生产单" + (production.isBlank() ? "" : " " + production)
                + "：没开始做的撤回或反审核后作废、重新排；已经领料、报工或回货的，结案后另下新单";
    }

    private String skuCode(OrderItem row) {
        StyleOrderInfo info = styleService.orderInfos(List.of(row.getStyleId())).get(row.getStyleId());
        return info == null ? "" : info.skus().stream().filter(k -> k.id() == row.getStyleSkuId()).map(StyleOrderInfo.Sku::code)
                .findFirst().orElse("");
    }

    private static List<OrderItem> rowsOf(Collection<OrderItem> rows, Long styleId) {
        return rows.stream().filter(r -> r.getStyleId().equals(styleId)).toList();
    }

    private static BizException stale() {
        return new BizException(ErrorCodes.STATE_CHANGED, "订单在这期间变了，作废这张变更单重新提");
    }

    // ---------- 导入、导出 ----------

    @Override
    public byte[] importTemplate() {
        return excel.template();
    }

    @Override
    public ImportResult importExcel(OrderImportHead head, MultipartFile file) {
        CompanyContext.create(head.getCompanyId());
        importHead(head);
        return imports.execute("order", "company:" + head.getCompanyId(), "order:order:import", file, head, () -> {
            var parsed = excel.read(file);
            parsed.forEach(order -> order.lines().forEach(line -> FieldPermissions.price(FieldPermissions.ORDER, null, line.price())));
            return parsed;
        }, parsed -> writeImport(head, parsed));
    }

    private Order importHead(OrderImportHead head) {
        Long me = LoginUser.currentUserId();
        OrderForm form = new OrderCreateReq();
        form.setCompanyId(head.getCompanyId());
        form.setOrderType(head.getOrderType());
        form.setBrand(head.getBrand());
        form.setOrderDate(LocalDate.now(clock));
        form.setRequiredDate(head.getRequiredDate());
        form.setSalesId(head.getSalesId() == null ? me : head.getSalesId());
        form.setMerchId(head.getMerchId() == null ? me : head.getMerchId());
        form.setCurrency("CNY");
        Order template = new Order();
        applyForm(template, form, null);
        return template;
    }

    private ImportResult writeImport(OrderImportHead head, List<OrderExcel.Parsed> orders) {
        Order template = importHead(head);
        List<String> notes = new ArrayList<>();
        int total = 0;
        for (OrderExcel.Parsed p : orders) {
            Order o = new Order();
            BeanUtils.copyProperties(template, o);
            o.setContractNo(p.contractNo());
            o.setSource(OrderSource.IMPORT);
            o.setStatus(DRAFT);
            List<OrderItem> items = new ArrayList<>();
            for (OrderExcel.Line l : p.lines()) {
                OrderItem i = new OrderItem();
                i.setSeq(items.size() + 1);
                i.setStyleId(l.styleId());
                i.setStyleSkuId(l.styleSkuId());
                i.setQty(l.qty());
                i.setPrice(Num.price(FieldPermissions.price(FieldPermissions.ORDER, null, l.price())));
                i.setAmount(Num.amount(l.qty(), i.getPrice()));
                i.setLineDueDate(l.lineDueDate());
                items.add(i);
            }
            insert(o, items);
            docLogService.add(BizType.ORDER, o.getId(), "import", null);
            total += items.size();
            long styles = items.stream().map(OrderItem::getStyleId).distinct().count();
            notes.add(o.getNo() + "：" + p.lines().getFirst().styleCode() + (styles > 1 ? " 等 " + styles + " 个款" : "")
                    + "，" + OrderTexts.pieces(o.getTotalQty()) + " 件");
        }
        return ImportResult.of(total, notes.stream().map(n -> new ImportError(0, null, n)).toList());
    }

    @Override
    public byte[] export(OrderQuery query) {
        query.setApprovalIds(query.isAuditForMe() ? approvalService.waitingForMe(BizType.ORDER) : List.of());
        Page<OrderRowVO> all = new Page<>(1, Excel.MAX_EXPORT_ROWS + 1, false);
        List<OrderRowVO> rows = orderMapper.selectRowPage(all, query, Keywords.codes(query.getKeyword()),
                DataScope.current(query.isMine())).getRecords();
        com.lingmao.scm.common.scope.ExportScope.check(query.getIds(), rows, OrderRowVO::getId, OrderRowVO::getCompanyId);
        fillRows(rows);
        return excel.export(rows);
    }

    // ---------- 给生产 ----------

    @Override
    public OrderForProduction lockForProduction(long orderId) {
        return forProduction(orderMapper.lockById(orderId));
    }

    @Override
    public OrderForProduction getForProduction(long orderId) {
        return forProduction(orderMapper.selectById(orderId));
    }

    private OrderForProduction forProduction(Order o) {
        if (o == null) {
            throw new BizException(404, NOT_FOUND);
        }
        long orderId = o.getId();
        List<OrderForProduction.Line> lines = itemMapper.selectByOrder(orderId).stream()
                .map(i -> new OrderForProduction.Line(i.getId(), i.getStyleId(), i.getStyleSkuId(), i.getQty(), i.getScheduledQty(),
                        i.getReturnedQty(), i.getTechPackId()))
                .toList();
        return new OrderForProduction(o.getId(), o.getNo(), o.getStatus(), o.getCompanyId(), o.getCommitDate(), o.getRequiredDate(),
                o.getMerchId(), lines);
    }

    @Override
    public void addScheduled(Map<Long, Integer> deltaByItem) {
        deltaByItem.forEach((id, delta) -> {
            if (delta != 0) {
                itemMapper.addScheduled(id, delta);
            }
        });
    }

    @Override
    public void addReturned(Map<Long, Integer> deltaByItem) {
        deltaByItem.forEach((id, delta) -> {
            if (delta != 0) {
                itemMapper.addReturned(id, delta);
            }
        });
    }

    @Override
    public void syncProduction(long orderId, ProductionProgress progress, String cause) {
        Order o = orderMapper.selectById(orderId);
        if (o == null || !PRODUCTION_DRIVEN.contains(o.getStatus())) {
            return;
        }
        DocStatus to = byProduction(itemMapper.selectByOrder(orderId), progress);
        if (to == o.getStatus()) {
            return;
        }
        StatusCas.update(orderMapper, new Order(), StatusCas.of(Order::getId, orderId, Order::getStatus, o.getStatus(), to)
                .setSql("version = version + 1"));
        docLogService.add(BizType.ORDER, orderId, "production", o.getStatus(), to, cause);
    }

    /** 按生产进度算的状态（PRD §6.7.5 规则 3）：每个 SKU 都排满、生产单都完成或结案 → 已完成；有审核过的生产单 → 生产中；否则已审核 */
    static DocStatus byProduction(List<OrderItem> items, ProductionProgress progress) {
        boolean scheduled = items.stream().allMatch(i -> i.getScheduledQty() >= i.getQty());
        if (scheduled && progress.allFinished()) {
            return DONE;
        }
        return progress.audited() ? RUNNING : APPROVED;
    }
}
