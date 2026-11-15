package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.ChangeVO;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lingmao.scm.module.supplier.entity.ChangeStatus.APPROVED;
import static com.lingmao.scm.module.supplier.entity.ChangeStatus.PENDING;
import static com.lingmao.scm.module.supplier.entity.ChangeStatus.REJECTED;
import static com.lingmao.scm.module.supplier.entity.ChangeStatus.WITHDRAWN;

/**
 * 关键字段改动的审批（RFC 004 §4）。三个动作都是比较并交换，一份改动只能被处理一次
 */
@Service
@RequiredArgsConstructor
public class SupplierChangeServiceImpl implements SupplierChangeService {

    /** 改动的转换表：只有待批的能批、驳、撤 */
    static final StatusFlow<ChangeStatus> FLOW = StatusFlow.of(ChangeStatus.class)
            .on("approve", PENDING).to(APPROVED)
            .on("reject", PENDING).to(REJECTED)
            .on("withdraw", PENDING).to(WITHDRAWN)
            .build();

    private static final String NOT_FOUND = "这份改动不存在，可能已经被撤回了";

    private final SupplierChangeMapper changeMapper;
    private final SupplierMapper supplierMapper;
    private final DocLogService docLogService;
    private final UserService userService;
    private final SupplierViews views;
    private final Clock clock;

    @Override
    public List<ChangeVO> list(long supplierId) {
        List<SupplierChange> changes = changeMapper.selectList(new LambdaQueryWrapper<SupplierChange>()
                .eq(SupplierChange::getSupplierId, supplierId)
                .orderByDesc(SupplierChange::getId));
        return toVOs(changes);
    }

    @Override
    public ChangeVO pending(long supplierId) {
        SupplierChange change = changeMapper.selectOne(new LambdaQueryWrapper<SupplierChange>()
                .eq(SupplierChange::getSupplierId, supplierId)
                .eq(SupplierChange::getStatus, PENDING));
        return change == null ? null : toVOs(List.of(change)).getFirst();
    }

    private List<ChangeVO> toVOs(List<SupplierChange> changes) {
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = new HashMap<>(userService.nicknames(changes.stream()
                .flatMap(c -> Stream.of(c.getApplyBy(), c.getAuditBy())).filter(Objects::nonNull).collect(Collectors.toSet())));
        boolean showAccount = SupplierPerms.has(SupplierPerms.BANK);
        Long me = LoginUser.currentUserId();
        List<ChangeVO> result = new ArrayList<>();
        for (SupplierChange c : changes) {
            result.add(new ChangeVO(c.getId(), c.getStatus(), views.display(c.getChanges(), showAccount),
                    c.getApplyBy(), names.get(c.getApplyBy()), c.getApplyTime(),
                    names.get(c.getAuditBy()), c.getAuditTime(), c.getRejectReason(), actions(c, me)));
        }
        return result;
    }

    /** 当前用户能对这份改动做的：申请人只能撤回；别人有准入权限的能批、驳（RFC 004 §4） */
    private static List<String> actions(SupplierChange c, Long me) {
        boolean applicant = Objects.equals(c.getApplyBy(), me);
        List<String> actions = new ArrayList<>();
        for (String action : FLOW.actions(c.getStatus())) {
            boolean allowed = action.equals("withdraw")
                    ? applicant && SupplierPerms.has(SupplierPerms.EDIT)
                    : !applicant && SupplierPerms.has(SupplierPerms.ADMIT);
            if (allowed) {
                actions.add(action);
            }
        }
        return actions;
    }

    @Override
    @Transactional
    public void approve(long supplierId, long changeId) {
        SupplierChange change = getChange(supplierId, changeId);
        ChangeStatus to = FLOW.next("approve", change.getStatus());
        ensureNotApplicant(change);
        // 先锁供应商、再动改动：和保存（锁供应商 → 插改动）同一个顺序，两边不会互相等成死锁
        Supplier supplier = supplierMapper.selectByIdForUpdate(supplierId);
        if (supplier == null) {
            throw new BizException(404, "供应商不存在，可能已经被删除了");
        }
        SupplierRules.ensureEditable(supplier.getStatus());

        // 1. 比较并交换 PENDING → APPROVED：两个人同时批，只有一个成功
        StatusCas.update(changeMapper, new SupplierChange(),
                StatusCas.of(SupplierChange::getId, changeId, SupplierChange::getStatus, change.getStatus(), to)
                        .set(SupplierChange::getAuditBy, LoginUser.currentUserId())
                        .set(SupplierChange::getAuditTime, OffsetDateTime.now(clock)));

        // 2. 逐项核对 before 还是不是现在的值：不是说明这期间被别的途径改了，整个回滚、改动还是待批
        for (ChangeItem item : change.getChanges()) {
            SupplierKeyField key = SupplierKeyField.of(item.field())
                    .orElseThrow(() -> new IllegalStateException("改动 " + changeId + " 里有不认识的字段 " + item.field()));
            if (!Objects.equals(key.text(supplier), item.before())) {
                throw new BizException(ErrorCodes.STATE_CHANGED, "资料已经变了（" + item.label() + "），请撤回这份改动、重新提交");
            }
        }

        // 3. 写回。信用代码先查一次说清和哪家重复，唯一索引兜底（两份改动改成同一个号，后批的在这里被拦住，RFC 004 §4.1）
        String credit = change.getChanges().stream()
                .filter(item -> item.field().equals(SupplierKeyField.CREDIT_CODE.field()))
                .map(ChangeItem::after).filter(Objects::nonNull).findFirst().orElse(null);
        SupplierRules.ensureCreditUnique(supplierMapper, credit, supplierId);
        LambdaUpdateWrapper<Supplier> writeBack = SupplierKeyField.writeBack(supplierId, change.getChanges());
        try {
            supplierMapper.update(new Supplier(), writeBack);
        } catch (DuplicateKeyException e) {
            throw new BizException("统一社会信用代码 " + credit + " 和别的供应商重复了，请驳回这份改动");
        }

        // 4. 操作记录：一串"账期 30 天 → 60 天"，账号遮
        docLogService.add(BizType.SUPPLIER, supplierId, "change-approve", views.describe(change.getChanges()));
    }

    @Override
    @Transactional
    public void reject(long supplierId, long changeId, String reason) {
        SupplierChange change = getChange(supplierId, changeId);
        ChangeStatus to = FLOW.next("reject", change.getStatus());
        ensureNotApplicant(change);
        StatusCas.update(changeMapper, new SupplierChange(),
                StatusCas.of(SupplierChange::getId, changeId, SupplierChange::getStatus, change.getStatus(), to)
                        .set(SupplierChange::getAuditBy, LoginUser.currentUserId())
                        .set(SupplierChange::getAuditTime, OffsetDateTime.now(clock))
                        .set(SupplierChange::getRejectReason, reason));
        docLogService.add(BizType.SUPPLIER, supplierId, "change-reject",
                views.describe(change.getChanges()) + "。原因：" + reason);
    }

    @Override
    @Transactional
    public void withdraw(long supplierId, long changeId) {
        SupplierChange change = getChange(supplierId, changeId);
        ChangeStatus to = FLOW.next("withdraw", change.getStatus());
        if (!Objects.equals(change.getApplyBy(), LoginUser.currentUserId())) {
            throw new BizException("只有提改动的人能撤回");
        }
        StatusCas.update(changeMapper, new SupplierChange(),
                StatusCas.of(SupplierChange::getId, changeId, SupplierChange::getStatus, change.getStatus(), to));
        docLogService.add(BizType.SUPPLIER, supplierId, "change-withdraw", views.describe(change.getChanges()));
    }

    /** 改动要属于这家供应商：路径里的两个 id 对不上当不存在 */
    private SupplierChange getChange(long supplierId, long changeId) {
        SupplierChange change = changeMapper.selectById(changeId);
        if (change == null || change.getSupplierId() != supplierId) {
            throw new BizException(404, NOT_FOUND);
        }
        return change;
    }

    /** 不能自己批自己提的改动，超级管理员也一样 */
    private static void ensureNotApplicant(SupplierChange change) {
        if (Objects.equals(change.getApplyBy(), LoginUser.currentUserId())) {
            throw new BizException("改动是你提的，要另一个有准入权限的人来批");
        }
    }
}
