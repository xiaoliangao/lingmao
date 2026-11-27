package com.lingmao.scm.module.settle.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.excel.Excel;
import com.lingmao.scm.common.excel.ExcelColumn;
import com.lingmao.scm.common.excel.ExcelSchema;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.CompanyService;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.dto.DocActionReq;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.settle.dto.CompareNotesReq;
import com.lingmao.scm.module.settle.dto.CompareResult;
import com.lingmao.scm.module.settle.dto.PendingStatementQuery;
import com.lingmao.scm.module.settle.dto.PendingStatementRow;
import com.lingmao.scm.module.settle.dto.StatementCreateReq;
import com.lingmao.scm.module.settle.dto.StatementCreatedVO;
import com.lingmao.scm.module.settle.dto.StatementDeductionReq;
import com.lingmao.scm.module.settle.dto.StatementDetailVO;
import com.lingmao.scm.module.settle.dto.StatementItemVO;
import com.lingmao.scm.module.settle.dto.StatementItemsReq;
import com.lingmao.scm.module.settle.dto.StatementQuery;
import com.lingmao.scm.module.settle.dto.StatementRowVO;
import com.lingmao.scm.module.settle.entity.Deduction;
import com.lingmao.scm.module.settle.entity.Statement;
import com.lingmao.scm.module.settle.entity.StatementItem;
import com.lingmao.scm.module.settle.entity.StatementSourceType;
import com.lingmao.scm.module.settle.entity.StatementStatus;
import com.lingmao.scm.module.settle.mapper.DeductionMapper;
import com.lingmao.scm.module.settle.mapper.StatementItemMapper;
import com.lingmao.scm.module.settle.mapper.StatementMapper;
import com.lingmao.scm.module.settle.service.StatementService;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.DataScope;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lingmao.scm.module.settle.entity.StatementStatus.CONFIRMED;
import static com.lingmao.scm.module.settle.entity.StatementStatus.DRAFT;
import static com.lingmao.scm.module.settle.entity.StatementStatus.SENT;
import static com.lingmao.scm.module.settle.entity.StatementStatus.VOIDED;

/**
 * 供应商对账单（RFC 019 §5.1、§5.3）。
 * <ul>
 *   <li>生成、刷新：StatementMapper 的三条查询拉"还没进对账单"的采购入库、采购退货明细、回货单、扣款登记（FOR SHARE / FOR UPDATE 锁住源单）；
 *       唯一索引兜底，两个人同时生成同一家的，后提交的报 1002</li>
 *   <li>明细变了（刷新、移出、加扣款）：重算合计、清掉比对结果、版本号 +1（StatementMapper.recompute）</li>
 *   <li>状态只按 FLOW 走；作废把明细的 active 置 false，单据回到"待对账"</li>
 *   <li>比对：TheirSheetReader 读对方的 Excel，StatementComparer 逐单比，结果存单头 compare_result，原件存附件（THEIR_STATEMENT）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class StatementServiceImpl implements StatementService {

    static final StatusFlow<StatementStatus> FLOW = StatusFlow.of(StatementStatus.class)
            .on("refresh", DRAFT).to(DRAFT)
            .on("removeItems", DRAFT).to(DRAFT)
            .on("addDeduction", DRAFT).to(DRAFT)
            .on("compare", DRAFT, SENT).to(DRAFT, SENT)
            .on("notes", DRAFT, SENT).to(DRAFT, SENT)
            .on("send", DRAFT).to(SENT)
            .on("withdraw", SENT).to(DRAFT)
            .on("confirm", SENT).to(CONFIRMED)
            .on("void", DRAFT).to(VOIDED)
            .build();

    static final String EDIT = "settle:statement:edit";
    static final String CONFIRM = "settle:statement:confirm";
    /** 对方对账单原件挂在对账单上的分组 */
    static final String THEIR_FILES = "THEIR_STATEMENT";
    private static final int MAX_PERIOD_DAYS = 366;
    private static final Set<String> ACTIONS = Set.of("send", "withdraw", "confirm", "void");

    private final StatementMapper statementMapper;
    private final StatementItemMapper itemMapper;
    private final DeductionMapper deductionMapper;
    private final CompanyService companyService;
    private final SupplierService supplierService;
    private final UserService userService;
    private final DictDataService dictDataService;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final FileService fileService;
    private final SerialNoGenerator serialNoGenerator;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    // ---------------------------------------------------------------- 查询

    @Override
    public PageResult<StatementRowVO> page(StatementQuery query) {
        IPage<StatementRowVO> page = statementMapper.selectRowPage(query.toPage(), query, DataScope.current(query.isMine()));
        fillCreators(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public List<PendingStatementRow> pending(PendingStatementQuery query) {
        return statementMapper.selectPending(query, LineScope.current());
    }

    @Override
    public StatementDetailVO detail(long id) {
        Statement s = visible(id);
        StatementDetailVO v = new StatementDetailVO();
        BeanUtils.copyProperties(statementMapper.selectRow(id), v);
        v.setVersion(s.getVersion());
        List<StatementItemVO> items = itemMapper.selectViews(id);
        v.setItems(items);
        v.setCompare(s.getCompareResult());
        SupplierRef partner = supplierService.refs(List.of(s.getPartnerId())).get(s.getPartnerId());
        if (partner != null) {
            v.setPartnerSettleMethod(partner.settleMethod());
            v.setPartnerCreditDays(partner.creditDays());
        }
        Map<Long, String> names = userService.nicknames(Stream.of(s.getCreateBy(), s.getSentBy(), s.getAuditBy())
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        v.setCreatorName(names.get(s.getCreateBy()));
        v.setSentBy(s.getSentBy());
        v.setSentByName(names.get(s.getSentBy()));
        v.setSentTime(s.getSentTime());
        v.setAuditBy(s.getAuditBy());
        v.setAuditByName(names.get(s.getAuditBy()));
        v.setAuditTime(s.getAuditTime());
        v.setConfirmNote(s.getConfirmNote());
        v.setVoidReason(s.getVoidReason());
        v.setActions(actions(s, !items.isEmpty()));
        return v;
    }

    /** 详情的 actions：状态允许的 ∩ 有权限的 ∩ 业务条件（空的不能发送；没比对过不用写说明） */
    static List<String> actions(Statement s, boolean hasItems) {
        boolean edit = LoginUser.hasAuthority(EDIT);
        boolean confirm = LoginUser.hasAuthority(CONFIRM);
        return FLOW.actions(s.getStatus()).stream()
                .filter(a -> a.equals("confirm") ? confirm : edit)
                .filter(a -> !a.equals("send") || hasItems)
                .filter(a -> !a.equals("notes") || s.getCompareResult() != null)
                .toList();
    }

    @Override
    public void checkVisible(long id) {
        visible(id);
    }

    private Statement visible(long id) {
        return visible(statementMapper.selectById(id));
    }

    /** 制单人范围 + 品类线：这家供应商做的线我都要能看（对账单是整家的账，里面有各条线的单价），不然当不存在（404） */
    private Statement visible(Statement s) {
        if (s == null) {
            throw new BizException(404, "对账单不存在");
        }
        DataScope.current(false).check(s.getCreateBy());
        if (!LineScope.current().canSeeAll(supplierService.productLines(s.getPartnerId()))) {
            throw new BizException(404, "对账单不存在");
        }
        return s;
    }

    /**
     * 生成前查品类线（RFC 019 §5.1）：对账是对整家的账，这家做的线我都能看才能对。
     * 有一条能看（下拉里选得到）报 1000 说清楚；一条都看不到当不存在
     */
    private void checkCovers(SupplierRef partner) {
        LineScope mine = LineScope.current();
        List<String> lines = supplierService.productLines(partner.id());
        if (mine.canSeeAll(lines)) {
            return;
        }
        if (!mine.canSeeAny(lines)) {
            throw new BizException(404, "供应商不存在");
        }
        Map<String, String> labels = dictDataService.labels(LineScope.DICT_TYPE);
        String others = lines.stream().filter(l -> !mine.canSee(l)).map(l -> labels.getOrDefault(l, l)).collect(Collectors.joining("、"));
        throw new BizException(partnerName(partner) + " 还做" + others + "，你看不到这几条线的单据：对这家的账，要找能看它全部品类线的人来生成");
    }

    /** 改之前：锁住单头、查范围、查状态、对版本号 */
    private Statement lockFor(long id, String action, Integer version) {
        Statement s = visible(statementMapper.lockById(id));
        CompanyContext.target(s.getCompanyId());
        if (!FLOW.allows(action, s.getStatus()) || (version != null && !version.equals(s.getVersion()))) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "对账单 " + s.getNo() + " 刚被别人改过，请刷新后再试");
        }
        return s;
    }

    // ---------------------------------------------------------------- 生成、刷新、移出、加扣款

    @Override
    @Transactional
    public StatementCreatedVO create(StatementCreateReq req) {
        CompanyContext.create(req.companyId());
        LocalDate from = req.periodStart();
        LocalDate to = req.periodEnd();
        if (to.isBefore(from)) {
            throw new BizException(400, "期间止不能早于期间起");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_PERIOD_DAYS) {
            throw new BizException(400, "一张对账单最长对一年，分几张对");
        }
        if (from.isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "期间还没开始，没有能对的单据");
        }
        companyService.getById(req.companyId());
        SupplierRef partner = supplierService.getRef(req.partnerId());
        checkCovers(partner);
        List<StatementItem> lines = collect(req.companyId(), req.partnerId(), from, to);
        if (lines.isEmpty()) {
            throw new BizException(partnerName(partner) + " 在 " + from + " 到 " + to
                    + " 没有要对的单据：已审核的采购入库、采购退货，已登记的回货，扣款登记，都已经进了对账单或者还没有");
        }
        Statement s = new Statement();
        s.setNo(serialNoGenerator.daily(BizType.STATEMENT.getPrefix()));
        s.setCompanyId(req.companyId());
        s.setPartnerId(req.partnerId());
        s.setPeriodStart(from);
        s.setPeriodEnd(to);
        s.setStatementDate(LocalDate.now(clock));
        s.setStatus(DRAFT);
        s.setTotalAmount(sum(lines, StatementItem::getAmount));
        s.setDeductAmount(sum(lines, StatementItem::getDeductAmount));
        s.setPayableAmount(s.getTotalAmount().subtract(s.getDeductAmount()));
        s.setRemark(blankToNull(req.remark()));
        statementMapper.insert(s);
        insertItems(s.getId(), lines, 1);
        docLogService.add(BizType.STATEMENT, s.getId(), "create", null, DRAFT, "生成，" + lines.size() + " 行");
        return new StatementCreatedVO(s.getId(), s.getNo(), 0, lines.size());
    }

    @Override
    @Transactional
    public StatementCreatedVO refresh(long id, int version) {
        Statement s = lockFor(id, "refresh", version);
        List<StatementItem> lines = collect(s.getCompanyId(), s.getPartnerId(), s.getPeriodStart(), s.getPeriodEnd());
        if (lines.isEmpty()) {
            return new StatementCreatedVO(id, s.getNo(), s.getVersion(), 0);
        }
        insertItems(id, lines, itemMapper.maxSeq(id) + 1);
        statementMapper.recompute(id, LoginUser.currentUserId());
        docLogService.add(BizType.STATEMENT, id, "refresh", "刷新，加进 " + lines.size() + " 行");
        return new StatementCreatedVO(id, s.getNo(), s.getVersion() + 1, lines.size());
    }

    @Override
    @Transactional
    public int removeItems(long id, StatementItemsReq req) {
        Statement s = lockFor(id, "removeItems", req.version());
        Set<Long> ids = new HashSet<>(req.itemIds());
        List<StatementItem> items = itemMapper.selectByStatement(id).stream().filter(i -> ids.contains(i.getId())).toList();
        if (items.size() != ids.size()) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "有几行已经不在对账单 " + s.getNo() + " 上了，请刷新后再试");
        }
        itemMapper.deleteByIds(ids);
        statementMapper.recompute(id, LoginUser.currentUserId());
        docLogService.add(BizType.STATEMENT, id, "removeItems", "移出 " + items.size() + " 行：" + describe(items));
        return s.getVersion() + 1;
    }

    @Override
    @Transactional
    public int addDeduction(long id, StatementDeductionReq req) {
        Statement s = lockFor(id, "addDeduction", req.version());
        LocalDate today = LocalDate.now(clock);
        // 不传日期取期间止；当月的对账单期间止还没到，取今天（和扣款登记一样不能晚于今天）
        LocalDate date = req.bizDate() != null ? req.bizDate() : s.getPeriodEnd().isAfter(today) ? today : s.getPeriodEnd();
        if (date.isBefore(s.getPeriodStart()) || date.isAfter(s.getPeriodEnd())) {
            throw new BizException(400, "扣款日期要在对账期间里（" + s.getPeriodStart() + " 到 " + s.getPeriodEnd() + "）");
        }
        if (date.isAfter(today)) {
            throw new BizException(400, "扣款日期不能晚于今天");
        }
        Deduction d = new Deduction();
        d.setCompanyId(s.getCompanyId());
        d.setPartnerId(s.getPartnerId());
        d.setBizDate(date);
        d.setAmount(req.amount());
        d.setReason(req.reason().strip());
        d.setRefNo(blankToNull(req.refNo()));
        deductionMapper.insert(d);
        StatementItem item = new StatementItem();
        item.setSourceType(StatementSourceType.DEDUCTION);
        item.setSourceId(d.getId());
        item.setSourceNo(d.getRefNo());
        item.setBizDate(date);
        item.setDescription(cut(d.getReason()));
        item.setAmount(BigDecimal.ZERO);
        item.setDeductAmount(req.amount());
        insertItems(id, List.of(item), itemMapper.maxSeq(id) + 1);
        statementMapper.recompute(id, LoginUser.currentUserId());
        docLogService.add(BizType.STATEMENT, id, "addDeduction", "加扣款 " + req.amount().toPlainString() + " 元：" + d.getReason());
        return s.getVersion() + 1;
    }

    /** 这段时间里这家还没进对账单的单据：退货记负数，回货按生产单单价算金额、次品扣款 */
    List<StatementItem> collect(long companyId, long partnerId, LocalDate from, LocalDate to) {
        List<StatementItem> out = new ArrayList<>();
        for (StatementItem i : statementMapper.selectPurchaseLines(companyId, partnerId, from, to)) {
            if (i.getSourceType() == StatementSourceType.PURCHASE_RETURN) {
                i.setQty(i.getQty() == null ? null : i.getQty().negate());
                i.setAmount(i.getAmount().negate());
            }
            out.add(i);
        }
        for (StatementItem r : statementMapper.selectReceipts(companyId, partnerId, from, to, LineScope.current())) {
            BigDecimal defects = r.getDeductAmount() == null ? BigDecimal.ZERO : r.getDeductAmount();
            r.setAmount(r.getQty().multiply(r.getPrice()).setScale(2, RoundingMode.HALF_UP));
            r.setDeductAmount(defects.multiply(r.getPrice()).setScale(2, RoundingMode.HALF_UP));
            if (defects.signum() > 0) {
                r.setDescription(cut(r.getDescription() + "，次品 " + defects.stripTrailingZeros().toPlainString()));
            }
            out.add(r);
        }
        out.addAll(statementMapper.selectDeductions(companyId, partnerId, from, to));
        out.sort(Comparator.comparing(StatementItem::getBizDate)
                .thenComparing(StatementItem::getSourceType)
                .thenComparing(StatementItem::getSourceId)
                .thenComparing(i -> i.getSourceItemId() == null ? 0L : i.getSourceItemId()));
        return out;
    }

    private void insertItems(long statementId, List<StatementItem> lines, int firstSeq) {
        int seq = firstSeq;
        try {
            for (StatementItem i : lines) {
                i.setStatementId(statementId);
                i.setSeq(seq++);
                i.setActive(true);
                if (i.getDeductAmount() == null) {
                    i.setDeductAmount(BigDecimal.ZERO);
                }
                itemMapper.insert(i);
            }
        } catch (DuplicateKeyException race) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "有人同时在对这家的账，这几张单据刚进了别的对账单，请刷新后再试");
        }
    }

    // ---------------------------------------------------------------- 状态动作

    @Override
    @Transactional
    public void act(long id, String action, DocActionReq req) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("对账单没有动作 " + action);
        }
        Statement s = lockFor(id, action, req.version());
        StatementStatus to = FLOW.next(action, s.getStatus());
        String reason = blankToNull(req.reason());
        OffsetDateTime now = OffsetDateTime.now(clock);
        Long me = LoginUser.currentUserId();
        var cas = StatusCas.of(Statement::getId, id, Statement::getStatus, s.getStatus(), to)
                .setSql("version = version + 1")
                .eq(Statement::getVersion, req.version());
        switch (action) {
            case "send" -> {
                if (itemMapper.maxSeq(id) == 0) {
                    throw new BizException("对账单 " + s.getNo() + " 是空的，没东西可发；不要了就作废");
                }
                cas.set(Statement::getSentBy, me).set(Statement::getSentTime, now);
            }
            // 撤回到草稿：发送人、时间清掉（详情不再显示"发送"），发过、撤过的经过在操作记录里
            case "withdraw" -> cas.setSql("sent_by = NULL, sent_time = NULL");
            case "confirm" -> {
                CompareResult compare = s.getCompareResult();
                List<CompareResult.Row> open = compare == null ? List.of() : compare.unexplained();
                if (!open.isEmpty()) {
                    throw new BizException(ErrorCodes.BIZ_ERROR, "和对方的对账单还有 " + open.size() + " 处差异没写说明，写了才能确认",
                            open.stream().map(StatementServiceImpl::describeRow).toList());
                }
                cas.set(Statement::getAuditBy, me).set(Statement::getAuditTime, now).set(Statement::getConfirmNote, reason);
            }
            case "void" -> cas.set(Statement::getVoidReason, reason).set(Statement::getVoidBy, me).set(Statement::getVoidTime, now);
            default -> {
            }
        }
        StatusCas.update(statementMapper, new Statement(), cas);
        if (to == VOIDED) {
            itemMapper.deactivate(id);
        }
        docLogService.add(BizType.STATEMENT, id, action, s.getStatus(), to, reason);
    }

    // ---------------------------------------------------------------- 比对

    @Override
    @Transactional
    public CompareResult compare(long id, int version, MultipartFile file) {
        Statement s = lockFor(id, "compare", version);
        String name = file.getOriginalFilename() == null ? "对方对账单.xlsx" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".xlsx") && !lower.endsWith(".xls")) {
            throw new BizException("现在只能读 Excel（.xls、.xlsx）；照片、PDF 要等识别服务开通（RFC 019 §5.4）");
        }
        if (file.getSize() > Excel.MAX_IMPORT_BYTES) {
            throw new BizException("文件超过 " + Excel.MAX_IMPORT_BYTES / 1024 / 1024 + "MB 了");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BizException("文件读不出来，请重新上传");
        }
        TheirSheetReader.Parsed parsed;
        try (Workbook workbook = Excel.openAny(new ByteArrayInputStream(bytes))) {
            parsed = TheirSheetReader.read(workbook, s.getPeriodEnd());
        } catch (IOException e) {
            throw new BizException("文件读不出来，请重新上传");
        }
        if (parsed.rows().isEmpty()) {
            throw new BizException("对方的对账单（" + parsed.sheetName() + "）里没读到有金额的行");
        }
        StatementComparer.Compared c = StatementComparer.compare(parsed.rows(), itemMapper.selectByStatement(id));
        Long me = LoginUser.currentUserId();
        String myName = me == null ? null : userService.nicknames(List.of(me)).get(me);
        BigDecimal theirTotal = c.theirTotal();
        CompareResult result = new CompareResult(name, OffsetDateTime.now(clock), me, myName, parsed.sheetName(), c.theirRowCount(),
                c.theirGoods(), c.theirDeduct(), theirTotal, s.getPayableAmount(), theirTotal.subtract(s.getPayableAmount()), c.rows());
        if (statementMapper.saveCompare(id, version, theirTotal, jsonMapper.writeValueAsString(result), me) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "对账单 " + s.getNo() + " 刚被别人改过，请刷新后再试");
        }
        FileVO saved = fileService.save(name, bytes);
        AttachmentReq original = new AttachmentReq();
        original.setUrl(saved.url());
        original.setName(saved.name());
        original.setSize(saved.size());
        attachmentService.replace(BizType.STATEMENT, id, THEIR_FILES, List.of(original));
        long differs = c.rows().stream().filter(CompareResult.Row::differs).count();
        docLogService.add(BizType.STATEMENT, id, "compare", "比对 " + name + "：一致 " + (c.rows().size() - differs) + " 处，不一致 "
                + differs + " 处；对方合计 " + theirTotal.toPlainString() + "，我方 " + s.getPayableAmount().toPlainString());
        return result;
    }

    @Override
    @Transactional
    public int saveNotes(long id, CompareNotesReq req) {
        Statement s = lockFor(id, "notes", req.version());
        CompareResult compare = s.getCompareResult();
        if (compare == null) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "对账单 " + s.getNo() + " 的比对结果已经清掉了（明细变过），请重新上传对方的对账单");
        }
        Map<String, String> notes = new LinkedHashMap<>();
        req.notes().forEach(n -> notes.put(n.key(), n.note() == null ? null : blankToNull(n.note())));
        List<CompareResult.Row> rows = compare.rows().stream()
                .map(r -> notes.containsKey(r.key()) ? r.withNote(notes.get(r.key())) : r).toList();
        if (statementMapper.saveCompare(id, req.version(), s.getTheirAmount(), jsonMapper.writeValueAsString(compare.withRows(rows)),
                LoginUser.currentUserId()) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "对账单 " + s.getNo() + " 刚被别人改过，请刷新后再试");
        }
        return s.getVersion() + 1;
    }

    @Override
    public List<AttachmentVO> theirFiles(long id) {
        visible(id);
        return attachmentService.list(BizType.STATEMENT, id, THEIR_FILES);
    }

    // ---------------------------------------------------------------- 导出

    /** 导出的一行；最后两行是合计、实付（对方传回来比对时，这两行按合计跳过） */
    @Data
    static class ExcelRow {
        private String type;
        private LocalDate date;
        private String no;
        private String deliveryNo;
        private String orderNo;
        private String description;
        private String unit;
        private BigDecimal qty;
        private BigDecimal price;
        private BigDecimal amount;
        private BigDecimal deduct;
    }

    static final ExcelSchema<ExcelRow> EXCEL = ExcelSchema.of("对账单", ExcelRow::new,
            ExcelColumn.text("类型", ExcelRow::getType, ExcelRow::setType),
            ExcelColumn.date("日期", ExcelRow::getDate, ExcelRow::setDate),
            ExcelColumn.text("单号", ExcelRow::getNo, ExcelRow::setNo),
            ExcelColumn.text("送货单号", ExcelRow::getDeliveryNo, ExcelRow::setDeliveryNo),
            ExcelColumn.text("订单号", ExcelRow::getOrderNo, ExcelRow::setOrderNo),
            ExcelColumn.text("摘要", ExcelRow::getDescription, ExcelRow::setDescription),
            ExcelColumn.text("单位", ExcelRow::getUnit, ExcelRow::setUnit),
            ExcelColumn.decimal("数量", 3, ExcelRow::getQty, ExcelRow::setQty),
            ExcelColumn.decimal("单价", 4, ExcelRow::getPrice, ExcelRow::setPrice),
            ExcelColumn.decimal("金额", 2, ExcelRow::getAmount, ExcelRow::setAmount),
            ExcelColumn.decimal("扣款", 2, ExcelRow::getDeduct, ExcelRow::setDeduct));

    @Override
    public byte[] export(long id) {
        Statement s = visible(id);
        Map<String, String> units = dictDataService.labels("unit");
        List<ExcelRow> rows = new ArrayList<>();
        for (StatementItem i : itemMapper.selectByStatement(id)) {
            ExcelRow r = new ExcelRow();
            r.setType(i.getSourceType().getLabel());
            r.setDate(i.getBizDate());
            r.setNo(i.getSourceNo());
            r.setDeliveryNo(i.getDeliveryNo());
            r.setOrderNo(i.getOrderNo());
            r.setDescription(i.getDescription());
            r.setUnit(i.getSourceType() == StatementSourceType.PRODUCTION_RECEIPT ? "件"
                    : i.getUnit() == null ? null : units.getOrDefault(i.getUnit(), i.getUnit()));
            r.setQty(i.getQty());
            r.setPrice(i.getPrice());
            r.setAmount(i.getSourceType().goods() ? i.getAmount() : null);
            r.setDeduct(i.getDeductAmount().signum() == 0 ? null : i.getDeductAmount());
            rows.add(r);
        }
        ExcelRow total = new ExcelRow();
        total.setType("合计");
        total.setAmount(s.getTotalAmount());
        total.setDeduct(s.getDeductAmount());
        rows.add(total);
        ExcelRow payable = new ExcelRow();
        payable.setType("实付");
        payable.setAmount(s.getPayableAmount());
        rows.add(payable);
        return Excel.export(EXCEL, rows);
    }

    @Override
    public String exportName(long id) {
        Statement s = visible(id);
        SupplierRef partner = supplierService.refs(List.of(s.getPartnerId())).get(s.getPartnerId());
        String who = partner == null ? "" : "-" + partnerName(partner).replaceAll("[\\\\/:*?\"<>|]", "");
        return "对账单-" + s.getNo() + who + ".xlsx";
    }

    // ---------------------------------------------------------------- 小工具

    private void fillCreators(List<? extends StatementRowVO> rows) {
        Map<Long, String> names = userService.nicknames(rows.stream().map(StatementRowVO::getCreateBy).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        rows.forEach(r -> r.setCreatorName(names.get(r.getCreateBy())));
    }

    static String partnerName(SupplierRef p) {
        return p.shortName() == null || p.shortName().isBlank() ? p.name() : p.shortName();
    }

    private static BigDecimal sum(List<StatementItem> items, Function<StatementItem, BigDecimal> f) {
        return items.stream().map(f).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 移出了哪几张：单号去重，最多列 10 个 */
    private static String describe(List<StatementItem> items) {
        List<String> nos = items.stream().map(i -> i.getSourceNo() != null ? i.getSourceNo() : i.getSourceType().getLabel() + " " + i.getDescription())
                .distinct().toList();
        return String.join("、", nos.subList(0, Math.min(10, nos.size()))) + (nos.size() > 10 ? " 等 " + nos.size() + " 张" : "");
    }

    /** 一处没写说明的差异，确认时列出来 */
    private static String describeRow(CompareResult.Row r) {
        String what = switch (r.outcome()) {
            case DIFF -> "不一致";
            case THEIRS_ONLY -> "对方有我方没有";
            case OURS_ONLY -> "我方有对方没有";
            case MATCH -> "一致";
        };
        String no = r.their() != null && r.their().no() != null ? r.their().no()
                : r.ours() != null && r.ours().no() != null ? r.ours().no()
                : r.their() != null ? r.their().description() : r.ours() == null ? "" : r.ours().description();
        return "第 " + r.key() + " 处 " + what + "：" + (no == null ? "" : no) + "，差 " + r.diffAmount().toPlainString() + " 元";
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
