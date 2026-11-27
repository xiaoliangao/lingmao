package com.lingmao.scm.module.quality.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingmao.scm.common.doc.DocStatus;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.scope.CompanyContext;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.biz.entity.Attachment;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.quality.dto.QcCreatedVO;
import com.lingmao.scm.module.quality.dto.QcOrderOption;
import com.lingmao.scm.module.quality.dto.QcPendingQuery;
import com.lingmao.scm.module.quality.dto.QcPendingRow;
import com.lingmao.scm.module.quality.dto.QcQuery;
import com.lingmao.scm.module.quality.dto.QcReq;
import com.lingmao.scm.module.quality.dto.QcReworkReq;
import com.lingmao.scm.module.quality.dto.QcRowVO;
import com.lingmao.scm.module.quality.dto.QcSourceVO;
import com.lingmao.scm.module.quality.dto.QcStatQuery;
import com.lingmao.scm.module.quality.dto.QcSupplierStat;
import com.lingmao.scm.module.quality.entity.QcInspection;
import com.lingmao.scm.module.quality.entity.QcKind;
import com.lingmao.scm.module.quality.entity.QcResult;
import com.lingmao.scm.module.quality.mapper.QcInspectionMapper;
import com.lingmao.scm.module.quality.service.QcInspectionService;
import com.lingmao.scm.module.settle.service.DeductionService;
import com.lingmao.scm.module.system.service.DictDataService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LineScope;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质检（RFC 019 §6）。一次检验记一条，不审核；回货的质检状态由记录推出来：
 * 没验 → 待检；最近一次合格、让步接收 → 验完了；不合格 → 安排返修 → 返修中 → 返修完成 → 待检，再验一次（attempt_no + 1）。
 * 状态只看回货单最近的一次：再验一次要先返修完成，所以前面几次都是"不合格、返修完成"；安排了返修的结论不能改、记录不能删，
 * 这样改哪一条都推不乱状态（安排返修、返修完成也只有最近的一次够条件）。
 * 质量扣款：填了金额就同一个事务里登记一笔扣款（DeductionService.createSourced），改、删跟着同步；进了对账单的金额、日期不能动
 */
@Service
@RequiredArgsConstructor
public class QcInspectionServiceImpl implements QcInspectionService {

    static final String DEFECT_DICT = "qc_defect_type";
    static final String DEDUCT_EDIT = "settle:deduction:edit";
    static final String SOURCE_TYPE = "QC_INSPECTION";
    /** 待检只列最近 30 天的入库、回货（返修完成等再验的不管日期）：没人验的旧单不一直挂着 */
    static final int PENDING_DAYS = 30;
    private static final int STAT_DAYS = 90;
    private static final Set<String> ORDER_OPEN = Set.of("APPROVED", "RUNNING", "DONE");

    /** AQL 2.5、一般检验水平 II：批量上限 → 抽检数、允收数（和原型一样） */
    private static final long[][] AQL = {{8, 2, 0}, {15, 3, 0}, {25, 5, 0}, {50, 8, 0}, {90, 13, 1}, {150, 20, 1},
            {280, 32, 2}, {500, 50, 3}, {1200, 80, 5}, {3200, 125, 7}, {10000, 200, 10}, {Long.MAX_VALUE, 315, 14}};

    private final QcInspectionMapper qcMapper;
    private final DeductionService deductionService;
    private final AttachmentService attachmentService;
    private final DictDataService dictDataService;
    private final UserService userService;
    private final SerialNoGenerator serialNoGenerator;
    private final Clock clock;

    // ---------------------------------------------------------------- 查

    @Override
    public PageResult<QcRowVO> page(QcQuery query) {
        IPage<QcRowVO> page = qcMapper.selectRowPage(query.toPage(), query, LineScope.current());
        fill(page.getRecords());
        return PageResult.of(page);
    }

    @Override
    public QcRowVO detail(long id) {
        QcRowVO row = qcMapper.selectRow(id);
        if (row == null || !visible(row.getProductLine())) {
            throw new BizException(404, "这条质检记录不存在");
        }
        fill(List.of(row));
        return row;
    }

    @Override
    public PageResult<QcPendingRow> pending(QcPendingQuery query) {
        return PageResult.of(qcMapper.selectPending(query.toPage(), query, since(), LineScope.current()));
    }

    @Override
    public List<QcRowVO> reworks(Long companyId) {
        List<QcRowVO> rows = qcMapper.selectReworks(companyId, LineScope.current());
        fill(rows);
        return rows;
    }

    @Override
    public QcSourceVO source(QcKind kind, long sourceId) {
        QcSourceVO s = load(kind, sourceId);
        List<QcRowVO> history = qcMapper.selectHistory(kind.name(), sourceId);
        int attempt = switch (kind) {
            case INCOMING -> {
                checkIncoming(s, history.isEmpty() ? null : history.getFirst().getNo());
                yield 1;
            }
            case FINAL -> {
                QcRowVO last = history.isEmpty() ? null : history.getLast();
                yield nextFinal(s, last == null ? null : new Attempt(last.getAttemptNo(), last.getResult(),
                        last.getReworkTime(), last.getReworkDoneTime()));
            }
            default -> {
                checkOrder(s);
                yield history.stream().mapToInt(QcRowVO::getAttemptNo).max().orElse(0) + 1;
            }
        };
        s.setAttemptNo(attempt);
        long[] aql = aql(s.getLotQty());
        if (aql != null) {
            s.setAqlSample(BigDecimal.valueOf(aql[0]));
            s.setAqlAccept(BigDecimal.valueOf(aql[1]));
        }
        fill(history);
        s.setHistory(history);
        return s;
    }

    @Override
    public List<QcOrderOption> orderOptions(String keyword) {
        return qcMapper.selectOrderOptions(keyword, LineScope.current());
    }

    @Override
    public List<QcSupplierStat> supplierStats(QcStatQuery query) {
        LocalDate today = LocalDate.now(clock);
        if (query.getDateTo() == null) {
            query.setDateTo(today);
        }
        if (query.getDateFrom() == null) {
            query.setDateFrom(query.getDateTo().minusDays(STAT_DAYS));
        }
        if (query.getDateFrom().isAfter(query.getDateTo())) {
            throw new BizException(400, "开始日期不能晚于结束日期");
        }
        LineScope lines = LineScope.current();
        List<QcSupplierStat> rows = qcMapper.selectSupplierStats(query, lines);
        Map<Long, List<String>> top = new LinkedHashMap<>();
        for (Map<String, Object> c : qcMapper.selectDefectCounts(query, lines)) {   // 每家按次数多的在前
            List<String> list = top.computeIfAbsent(((Number) c.get("partnerId")).longValue(), k -> new ArrayList<>());
            if (list.size() < 3) {
                list.add((String) c.get("defectType"));
            }
        }
        rows.forEach(r -> r.setTopDefects(top.getOrDefault(r.getPartnerId(), List.of())));
        return rows;
    }

    @Override
    public long pendingCount() {
        return qcMapper.countPending(since(), LineScope.current());
    }

    @Override
    public long reworkPendingCount() {
        return qcMapper.countReworkPending(LineScope.current());
    }

    // ---------------------------------------------------------------- 记、改、删

    @Override
    @Transactional
    public QcCreatedVO create(QcReq req) {
        if (req.kind() == null || req.sourceId() == null) {
            throw new BizException(400, "检验种类和来源单必填");
        }
        QcKind kind = req.kind();
        QcSourceVO s = load(kind, req.sourceId());
        CompanyContext.create(s.getCompanyId());
        validate(req, kind);
        if (req.deductAmount() != null) {
            checkDeductAllowed();
        }
        int attempt = switch (kind) {
            case INCOMING -> {
                checkIncoming(s, qcMapper.incomingNo(s.getSourceId()));
                yield 1;
            }
            case FINAL -> {
                QcInspection last = qcMapper.lockLatestFinal(s.getSourceId());
                yield nextFinal(s, last == null ? null : new Attempt(last.getAttemptNo(), last.getResult(),
                        last.getReworkTime(), last.getReworkDoneTime()));
            }
            default -> {
                checkOrder(s);
                yield qcMapper.maxOrderAttempt(s.getSourceId(), kind.name()) + 1;
            }
        };
        QcInspection q = new QcInspection();
        q.setNo(serialNoGenerator.daily(BizType.QC_INSPECTION.prefix(null)));
        q.setCompanyId(s.getCompanyId());
        q.setKind(kind);
        q.setInvDocId(kind == QcKind.INCOMING ? s.getSourceId() : null);
        q.setPrdReceiptId(kind == QcKind.FINAL ? s.getSourceId() : null);
        q.setPrdOrderId(s.getPrdOrderId());
        q.setAttemptNo(attempt);
        q.setPartnerId(s.getPartnerId());
        q.setProductLine(s.getProductLine());
        q.setTargetDesc(s.getTargetDesc());
        q.setLotQty(s.getLotQty());
        q.setUnit(s.getUnit());
        long[] aql = aql(s.getLotQty());
        q.setAcceptQty(aql == null ? null : BigDecimal.valueOf(aql[1]));
        q.setInspectDate(req.inspectDate());
        q.setSampleQty(req.sampleQty());
        q.setDefectQty(defectQty(req));
        q.setResult(req.result());
        q.setDefectTypes(defectTypes(req.defectTypes(), List.of()));
        q.setNote(note(req.note()));
        qcMapper.insert(q);
        attachmentService.replace(BizType.QC_INSPECTION, q.getId(), Attachment.IMAGE, photos(req));
        if (req.deductAmount() != null) {
            long deductionId = deductionService.createSourced(sourced(q, req.deductAmount()));
            qcMapper.update(null, new LambdaUpdateWrapper<QcInspection>().eq(QcInspection::getId, q.getId())
                    .set(QcInspection::getDeductionId, deductionId));
        }
        return new QcCreatedVO(q.getId(), q.getNo());
    }

    @Override
    @Transactional
    public int update(long id, QcReq req) {
        QcInspection q = lock(id, req.version());
        if (q.getReworkTime() != null && req.result() != QcResult.FAIL) {
            throw new BizException("已经安排了返修，结论不能再改：返修回来点「返修完成」再验一次，记新的结论");
        }
        validate(req, q.getKind());
        int updated = qcMapper.update(null, new LambdaUpdateWrapper<QcInspection>()
                .eq(QcInspection::getId, id)
                .eq(QcInspection::getVersion, q.getVersion())
                .set(QcInspection::getInspectDate, req.inspectDate())
                .set(QcInspection::getSampleQty, req.sampleQty())
                .set(QcInspection::getDefectQty, defectQty(req))
                .set(QcInspection::getResult, req.result())
                .set(QcInspection::getDefectTypes, defectTypes(req.defectTypes(), q.getDefectTypes()),
                        "typeHandler=com.lingmao.scm.common.mybatis.StringArrayTypeHandler")
                .set(QcInspection::getNote, note(req.note()))
                .setSql("version = version + 1"));
        if (updated == 0) {
            throw changed();
        }
        q.setInspectDate(req.inspectDate());
        q.setResult(req.result());
        q.setNote(note(req.note()));
        syncDeduction(q, req);
        attachmentService.replace(BizType.QC_INSPECTION, id, Attachment.IMAGE, photos(req));
        return q.getVersion() + 1;
    }

    @Override
    @Transactional
    public void delete(long id) {
        QcInspection q = lock(id, null);
        if (q.getReworkTime() != null) {
            throw new BizException("已经安排了返修，不能删：返修回来点「返修完成」再验一次");
        }
        if (q.getDeductionId() != null && !LoginUser.hasAuthority(DEDUCT_EDIT)) {
            throw new BizException(403, "这条记了质量扣款，删了扣款也一起删：要有扣款登记权限的人来删");
        }
        attachmentService.deleteByDoc(BizType.QC_INSPECTION, id);
        qcMapper.deleteById(id);
        if (q.getDeductionId() != null) {
            deductionService.deleteSourced(q.getDeductionId());   // 进了对账单的报 1000，整个回滚
        }
    }

    @Override
    @Transactional
    public int rework(long id, QcReworkReq req) {
        QcInspection q = lock(id, req.version());
        if (q.getKind() != QcKind.FINAL || q.getResult() != QcResult.FAIL) {
            throw new BizException("只有尾期验货不合格的回货能安排返修");
        }
        if (q.getReworkTime() != null) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "已经安排过返修了，请刷新");
        }
        if (req.note() == null || req.note().isBlank()) {
            throw new BizException(400, "要写返修说明：返修什么、多少件、工厂哪天送回");
        }
        return move(q, new LambdaUpdateWrapper<QcInspection>()
                .set(QcInspection::getReworkNote, req.note().strip())
                .set(QcInspection::getReworkBy, LoginUser.currentUserId())
                .set(QcInspection::getReworkTime, OffsetDateTime.now(clock)));
    }

    @Override
    @Transactional
    public int reworkDone(long id, QcReworkReq req) {
        QcInspection q = lock(id, req.version());
        if (q.getReworkTime() == null || q.getReworkDoneTime() != null) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "这批回货现在不在返修中，请刷新");
        }
        return move(q, new LambdaUpdateWrapper<QcInspection>()
                .set(QcInspection::getReworkDoneBy, LoginUser.currentUserId())
                .set(QcInspection::getReworkDoneTime, OffsetDateTime.now(clock)));
    }

    // ---------------------------------------------------------------- 规则

    /** 回货单最近一次尾期验货的样子（锁住的实体或历史里的最后一条） */
    record Attempt(int no, QcResult result, OffsetDateTime reworkTime, OffsetDateTime reworkDoneTime) {
    }

    /** 回货单这次是第几次验：没验过是 1，返修完成的是下一次；验完了、不合格没返修、在返修的报 1000 */
    static int nextFinal(QcSourceVO s, Attempt last) {
        if (!DocStatus.APPROVED.name().equals(s.getSourceStatus())) {
            throw new BizException("回货单 " + s.getSourceNo() + " 是「" + statusLabel(s.getSourceStatus()) + "」，已审核的回货才能验");
        }
        if (last == null) {
            return 1;
        }
        String head = "回货单 " + s.getSourceNo() + " 第 " + last.no() + " 次验货";
        if (last.result() != QcResult.FAIL) {
            throw new BizException(head + "已经" + last.result().getLabel() + "了：要改就改那条记录");
        }
        if (last.reworkTime() == null) {
            throw new BizException(head + "不合格，还没安排返修：先在「不合格、返修」里点「返修」");
        }
        if (last.reworkDoneTime() == null) {
            throw new BizException("回货单 " + s.getSourceNo() + " 在返修：返修回来点「返修完成」再验");
        }
        return last.no() + 1;
    }

    private static void checkIncoming(QcSourceVO s, String doneNo) {
        if (!DocStatus.APPROVED.name().equals(s.getSourceStatus())) {
            throw new BizException("入库单 " + s.getSourceNo() + " 是「" + statusLabel(s.getSourceStatus()) + "」，已审核的采购入库才能验");
        }
        if (s.getPartnerId() == null) {
            throw new BizException("入库单 " + s.getSourceNo() + " 没有供应商，记不了来料检验");
        }
        if (doneNo != null) {
            throw new BizException("入库单 " + s.getSourceNo() + " 已经验过了（" + doneNo + "）：要改就改那条记录");
        }
    }

    private static void checkOrder(QcSourceVO s) {
        if (!ORDER_OPEN.contains(s.getSourceStatus())) {
            throw new BizException("生产单 " + s.getSourceNo() + " 是「" + statusLabel(s.getSourceStatus()) + "」，审核了才能记产前样、中检");
        }
    }

    /** 必填、数量、结论的规则（新建、改共用） */
    void validate(QcReq req, QcKind kind) {
        if (req.inspectDate().isAfter(LocalDate.now(clock))) {
            throw new BizException(400, "检验日期不能晚于今天");
        }
        if (kind != QcKind.PRE_PRODUCTION && (req.sampleQty() == null || req.sampleQty().signum() <= 0)) {
            throw new BizException(400, "抽检数要填");
        }
        if (req.defectQty() != null && req.defectQty().signum() > 0 && req.sampleQty() == null) {
            throw new BizException(400, "填了不合格数，抽检数也要填");
        }
        if (req.sampleQty() != null && req.defectQty() != null && req.defectQty().compareTo(req.sampleQty()) > 0) {
            throw new BizException(400, "不合格数不能大于抽检数");
        }
        if (kind == QcKind.PRE_PRODUCTION && req.result() == QcResult.CONCESSION) {
            throw new BizException(400, "产前样只有合格（通过）、不合格（不通过）");
        }
        if (req.result() != QcResult.PASS && (req.note() == null || req.note().isBlank())) {
            throw new BizException(400, "不合格、让步接收要写问题");
        }
        if (req.result() == QcResult.PASS && req.deductAmount() != null && LoginUser.hasAuthority(DEDUCT_EDIT)) {
            throw new BizException(400, "合格的不扣款：扣款金额空着");
        }
    }

    /** 改的时候同步扣款。有扣款权限的按请求里的金额加、改、去掉；没有的不看请求里的金额，只同步日期、原因 */
    private void syncDeduction(QcInspection q, QcReq req) {
        Long deductionId = q.getDeductionId();
        if (!LoginUser.hasAuthority(DEDUCT_EDIT)) {
            if (deductionId == null) {
                return;
            }
            if (q.getResult() == QcResult.PASS) {
                throw new BizException("这条记了质量扣款，改成合格要先请有扣款登记权限的人把扣款去掉");
            }
            BigDecimal current = qcMapper.selectRow(q.getId()).getDeductAmount();
            deductionService.updateSourced(deductionId, sourced(q, current));
            return;
        }
        BigDecimal amount = req.deductAmount();
        if (deductionId == null && amount != null) {
            long created = deductionService.createSourced(sourced(q, amount));
            qcMapper.update(null, new LambdaUpdateWrapper<QcInspection>().eq(QcInspection::getId, q.getId())
                    .set(QcInspection::getDeductionId, created));
        } else if (deductionId != null && amount == null) {
            qcMapper.update(null, new LambdaUpdateWrapper<QcInspection>().eq(QcInspection::getId, q.getId())
                    .set(QcInspection::getDeductionId, null));
            deductionService.deleteSourced(deductionId);
        } else if (deductionId != null) {
            deductionService.updateSourced(deductionId, sourced(q, amount));
        }
    }

    private DeductionService.Sourced sourced(QcInspection q, BigDecimal amount) {
        String reason = "质检 " + q.getNo() + "：" + q.getKind().getLabel() + q.getResult().getLabel()
                + (q.getNote() == null ? "" : "，" + q.getNote());
        return new DeductionService.Sourced(SOURCE_TYPE, q.getId(), q.getCompanyId(), q.getPartnerId(), q.getInspectDate(),
                amount, reason, q.getNo());
    }

    private void checkDeductAllowed() {
        if (!LoginUser.hasAuthority(DEDUCT_EDIT)) {
            throw new BizException(403, "没有扣款登记的权限，扣款金额要空着：记下问题，请理单、跟单在这条记录上补扣款");
        }
    }

    private int move(QcInspection q, LambdaUpdateWrapper<QcInspection> set) {
        int updated = qcMapper.update(null, set.eq(QcInspection::getId, q.getId())
                .eq(QcInspection::getVersion, q.getVersion())
                .setSql("version = version + 1"));
        if (updated == 0) {
            throw changed();
        }
        return q.getVersion() + 1;
    }

    /** 锁住，查品类线、主体、版本号（delete 不带版本号，传 null） */
    private QcInspection lock(long id, Integer version) {
        QcInspection q = qcMapper.lockById(id);
        if (q == null || !visible(q.getProductLine())) {
            throw new BizException(404, "这条质检记录不存在");
        }
        CompanyContext.target(q.getCompanyId());
        if (version != null && !version.equals(q.getVersion())) {
            throw changed();
        }
        return q;
    }

    private QcSourceVO load(QcKind kind, long sourceId) {
        QcSourceVO s = switch (kind) {
            case INCOMING -> qcMapper.selectIncomingSource(sourceId);
            case FINAL -> qcMapper.selectReceiptSource(sourceId);
            default -> qcMapper.selectOrderSource(sourceId);
        };
        if (s == null || !visible(s.getProductLine())) {
            throw new BizException(404, switch (kind) {
                case INCOMING -> "采购入库单不存在";
                case FINAL -> "回货单不存在";
                default -> "生产单不存在";
            });
        }
        s.setKind(kind);
        return s;
    }

    /** 品类线：来料检验没有线，大家都能看 */
    private static boolean visible(String line) {
        return line == null || LineScope.current().canSee(line);
    }

    private List<String> defectTypes(List<String> values, List<String> previous) {
        if (values == null || values.stream().allMatch(v -> v == null || v.isBlank())) {
            return List.of();
        }
        return dictDataService.requireValues(DEFECT_DICT, values, previous == null ? List.of() : previous, "问题类型");
    }

    private static BigDecimal defectQty(QcReq req) {
        return req.defectQty() == null && req.sampleQty() != null ? BigDecimal.ZERO : req.defectQty();
    }

    private static List<AttachmentReq> photos(QcReq req) {
        return req.photos() == null ? List.of() : req.photos();
    }

    private static String note(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }

    /** AQL 建议：抽检数（不超过批量）、允收数；批量不知道返回 null */
    static long[] aql(BigDecimal lot) {
        if (lot == null || lot.signum() <= 0) {
            return null;
        }
        for (long[] row : AQL) {
            if (lot.compareTo(BigDecimal.valueOf(row[0])) <= 0) {
                long whole = Math.max(1, lot.setScale(0, RoundingMode.HALF_UP).longValue());
                return new long[]{Math.min(row[1], whole), row[2]};
            }
        }
        return null;
    }

    private LocalDate since() {
        return LocalDate.now(clock).minusDays(PENDING_DAYS);
    }

    private static String statusLabel(String status) {
        try {
            return DocStatus.valueOf(status).getLabel();
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(status);
        }
    }

    private static BizException changed() {
        return new BizException(ErrorCodes.STATE_CHANGED, "这条质检记录刚被别人改过，请刷新后再试");
    }

    /** 检验人、返修人的名字，照片 */
    private void fill(List<QcRowVO> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Set<Long> users = new HashSet<>();
        rows.forEach(r -> {
            users.add(r.getCreateBy());
            users.add(r.getReworkBy());
            users.add(r.getReworkDoneBy());
        });
        users.remove(null);
        Map<Long, String> names = userService.nicknames(users);
        Map<Long, List<AttachmentVO>> photos = attachmentService.lists(BizType.QC_INSPECTION,
                rows.stream().map(QcRowVO::getId).toList(), Attachment.IMAGE);
        rows.forEach(r -> {
            r.setInspectorName(names.get(r.getCreateBy()));
            r.setReworkByName(r.getReworkBy() == null ? null : names.get(r.getReworkBy()));
            r.setReworkDoneByName(r.getReworkDoneBy() == null ? null : names.get(r.getReworkDoneBy()));
            r.setPhotos(photos.getOrDefault(r.getId(), List.of()));
            if (r.getDefectTypes() == null) {
                r.setDefectTypes(List.of());
            }
        });
    }
}
