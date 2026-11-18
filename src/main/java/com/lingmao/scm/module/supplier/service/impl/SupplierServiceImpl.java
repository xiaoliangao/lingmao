package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmao.scm.common.doc.StatusCas;
import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.common.mybatis.Updates;
import com.lingmao.scm.common.page.PageResult;
import com.lingmao.scm.common.page.Keywords;
import com.lingmao.scm.common.seq.SerialNoGenerator;
import com.lingmao.scm.module.basic.service.WarehouseService;
import com.lingmao.scm.module.biz.dto.DocLogVO;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.biz.service.AttachmentService;
import com.lingmao.scm.module.biz.service.DocLogService;
import com.lingmao.scm.module.supplier.dto.BankVO;
import com.lingmao.scm.module.supplier.dto.SupplierOption;
import com.lingmao.scm.module.supplier.dto.SupplierQuery;
import com.lingmao.scm.module.supplier.dto.SupplierRef;
import com.lingmao.scm.module.supplier.dto.SupplierRowVO;
import com.lingmao.scm.module.supplier.dto.SupplierSaveReq;
import com.lingmao.scm.module.supplier.dto.SupplierSaveResult;
import com.lingmao.scm.module.supplier.dto.SupplierVO;
import com.lingmao.scm.module.supplier.entity.ChangeItem;
import com.lingmao.scm.module.supplier.entity.ChangeStatus;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierCategoryRow;
import com.lingmao.scm.module.supplier.entity.SupplierCert;
import com.lingmao.scm.module.supplier.entity.SupplierChange;
import com.lingmao.scm.module.supplier.entity.SupplierGoodAtRow;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierCertMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierChangeMapper;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierChangeService;
import com.lingmao.scm.module.supplier.service.SupplierService;
import com.lingmao.scm.module.system.service.UserService;
import com.lingmao.scm.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ADMITTING;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.BLACKLISTED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.DISABLED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;

/**
 * 供应商（RFC 004）。状态规则只写在 FLOW 和 SupplierRules 里；改状态一律比较并交换，每个动作写一条操作记录。
 * <p>
 * 工厂仓（RFC 004 §6）：新建、保存、删除时在同一个事务里调 basic 的 WarehouseService
 */
@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    /** 状态转换表（RFC 004 §2）。按钮顺序就是这里的顺序 */
    static final StatusFlow<SupplierStatus> FLOW = StatusFlow.of(SupplierStatus.class)
            .on("submit", PENDING_ADMIT).to(ADMITTING)
            .on("withdraw", ADMITTING).to(PENDING_ADMIT)
            .on("audit", ADMITTING).to(ACTIVE)
            .on("reject", ADMITTING).to(PENDING_ADMIT)
            .on("disable", ACTIVE).to(DISABLED)
            .on("enable", DISABLED).to(ACTIVE)
            .on("blacklist", PENDING_ADMIT, ADMITTING, ACTIVE, DISABLED).to(BLACKLISTED)
            // 两个去向：准入过的回停用，从没准入过的回待准入（RFC 004 §2，堵住"待准入 → 拉黑 → 解除 → 启用"绕过准入）
            .on("unblacklist", BLACKLISTED).to(DISABLED, PENDING_ADMIT)
            .build();

    /** 各动作要的权限码（RFC 004 §2 的表）：详情的 actions 按它筛 */
    private static final Map<String, String> ACTION_PERMS = Map.of(
            "submit", SupplierPerms.EDIT,
            "withdraw", SupplierPerms.EDIT,
            "audit", SupplierPerms.ADMIT,
            "reject", SupplierPerms.ADMIT,
            "disable", SupplierPerms.EDIT,
            "enable", SupplierPerms.EDIT,
            "blacklist", SupplierPerms.ADMIT,
            "unblacklist", SupplierPerms.ADMIT);

    /** 下拉最多给多少条：再多就该多打几个字了 */
    static final int OPTIONS_LIMIT = 50;

    private static final String NOT_FOUND = "供应商不存在，可能已经被删除了";

    private final SupplierMapper supplierMapper;
    private final SupplierCertMapper certMapper;
    private final SupplierChangeMapper changeMapper;
    private final SupplierChangeService changeService;
    private final SerialNoGenerator serialNoGenerator;
    private final DocLogService docLogService;
    private final AttachmentService attachmentService;
    private final UserService userService;
    private final SupplierDicts dicts;
    private final SupplierViews views;
    private final SupplierLists lists;
    private final GoodAtCategories goodAtCategories;
    private final WarehouseService warehouseService;
    private final Clock clock;

    // ------------------------------------------------------------------ 查

    @Override
    public PageResult<SupplierRowVO> page(SupplierQuery query) {
        Page<Supplier> page = supplierMapper.selectPage(query.toPage(), lists.wrapper(query));
        return new PageResult<>(lists.rows(page.getRecords()), page.getTotal());
    }

    @Override
    public SupplierVO detail(long id) {
        Supplier s = getEntity(id);
        SupplierVO vo = new SupplierVO();
        BeanUtils.copyProperties(s, vo);
        vo.setBankAccount(BankAccounts.mask(s.getBankAccount()));
        vo.setCategories(dicts.sorted(SupplierDicts.SUPPLIER_CATEGORY, supplierMapper.selectCategories(id)));
        vo.setGoodAt(supplierMapper.selectGoodAtOf(List.of(id)).stream()
                .map(g -> new SupplierVO.GoodAtVO(g.getCategoryId(), g.getCategoryName())).toList());
        Map<Long, String> names = new HashMap<>(userService.nicknames(Stream.of(s.getBuyerId(), s.getAdmitApplyBy(), s.getAdmitBy(),
                s.getBlackBy(), s.getCreateBy()).filter(Objects::nonNull).collect(Collectors.toSet())));
        vo.setBuyerName(names.get(s.getBuyerId()));
        vo.setAdmitApplyByName(names.get(s.getAdmitApplyBy()));
        vo.setAdmitByName(names.get(s.getAdmitBy()));
        vo.setBlackByName(names.get(s.getBlackBy()));
        vo.setCreateByName(names.get(s.getCreateBy()));
        vo.setPendingChange(changeService.pending(id));
        vo.setActions(actions(s));
        return vo;
    }

    /** 当前用户能做的 = 转换表允许的 ∩ 有权限的 ∩ 业务条件（架构 §4.2） */
    List<String> actions(Supplier s) {
        Long me = LoginUser.currentUserId();
        boolean applicant = Objects.equals(s.getAdmitApplyBy(), me);
        List<String> actions = new ArrayList<>();
        for (String action : FLOW.actions(s.getStatus())) {
            if (!SupplierPerms.has(ACTION_PERMS.get(action))) {
                continue;
            }
            // 只有申请人能撤回；申请人不能批自己的（RFC 004 §2）
            if (action.equals("withdraw") && !applicant || (action.equals("audit") || action.equals("reject")) && applicant) {
                continue;
            }
            actions.add(action);
        }
        if (SupplierRules.EDITABLE.contains(s.getStatus()) && SupplierPerms.has(SupplierPerms.EDIT)) {
            actions.add("edit");
        }
        if (!SupplierRules.UNDELETABLE.containsKey(s.getStatus()) && SupplierPerms.has(SupplierPerms.REMOVE)) {
            actions.add("remove");
        }
        return actions;
    }

    @Override
    public BankVO bank(long id) {
        Supplier s = getEntity(id);
        return new BankVO(s.getBankName(), s.getBankAccount(), s.getBankAccountName());
    }

    @Override
    public List<DocLogVO> logs(long id) {
        getEntity(id);
        return docLogService.list(BizType.SUPPLIER, id);
    }

    // ------------------------------------------------------------------ 新建、保存、删除

    @Override
    @Transactional
    public SupplierSaveResult create(SupplierSaveReq req) {
        Supplier s = new Supplier();
        applyNormal(s, req);
        applyKey(s, req, null);
        List<String> categories = distinct(req.getCategories());
        List<Long> goodAt = distinct(req.getGoodAtIds());
        validate(s, null, categories, List.of(), goodAt, List.of());

        s.setStatus(PENDING_ADMIT);
        s.setCode(nextCode());
        supplierMapper.insert(s);
        long id = s.getId();
        insertCategories(id, categories);
        insertGoodAt(id, goodAt);
        docLogService.add(BizType.SUPPLIER, id, "create", null, PENDING_ADMIT, null);
        if (SupplierDicts.hasFactory(categories)) {
            warehouseService.syncFactoryWarehouse(id, s.getCode(), s.getShortName(), true);
        }
        return new SupplierSaveResult(id, List.of(), List.of());
    }

    /** GYS + 4 位流水；取到的号被导入的领猫编码占了，再取下一个（RFC 001 §6.1） */
    private String nextCode() {
        while (true) {
            String code = serialNoGenerator.next(BizType.SUPPLIER.getPrefix(), 4);
            if (!supplierMapper.exists(new LambdaQueryWrapper<Supplier>().eq(Supplier::getCode, code))) {
                return code;
            }
        }
    }

    @Override
    @Transactional
    public SupplierSaveResult update(long id, SupplierSaveReq req) {
        // 先锁住这一行：和申请准入、别人的保存排队，下面"现在是什么状态、关键字段原来是什么"才靠得住
        Supplier current = lock(id);
        SupplierRules.ensureEditable(current.getStatus());
        List<String> currentCategories = supplierMapper.selectCategories(id);
        List<Long> currentGoodAt = supplierMapper.selectGoodAtOf(List.of(id)).stream()
                .map(SupplierGoodAtRow::getCategoryId).toList();

        Supplier target = new Supplier();
        BeanUtils.copyProperties(current, target);
        applyNormal(target, req);
        applyKey(target, req, current);
        List<String> categories = distinct(req.getCategories());
        List<Long> goodAt = distinct(req.getGoodAtIds());
        validate(target, current, categories, currentCategories, goodAt, currentGoodAt);

        // 1. 普通字段整体覆盖（关键字段、状态标了 NEVER，这里写不到）。条件带 status <> 'ADMITTING'（RFC 004 §2.2）
        if (supplierMapper.update(target, Updates.replaceById(target).ne("status", ADMITTING.name())) == 0) {
            throw new BizException(SupplierRules.ADMITTING_LOCKED);
        }

        // 2. 关键字段：没变的去掉；信用代码从空补上的直接生效（用户 11-15 定）；待准入的直接改；其余挂起等审批
        List<ChangeItem> changed = new ArrayList<>(SupplierKeyField.diff(current, target));
        List<ChangeItem> applied = new ArrayList<>();
        List<ChangeItem> pending = new ArrayList<>();
        ChangeItem credit = changed.stream()
                .filter(item -> item.field().equals(SupplierKeyField.CREDIT_CODE.field())).findFirst().orElse(null);
        if (credit != null && current.getCreditCode() == null) {
            writeKeys(SupplierKeyField.writeBack(id, List.of(credit)).isNull(Supplier::getCreditCode));
            applied.add(credit);
            changed.remove(credit);
        }
        if (!changed.isEmpty()) {
            if (SupplierRules.KEY_DIRECT.contains(current.getStatus())) {
                writeKeys(SupplierKeyField.writeBack(id, changed).eq(Supplier::getStatus, PENDING_ADMIT));
                applied.addAll(changed);
            } else {
                propose(id, changed);
                pending.addAll(changed);
            }
        }

        // 3. 分类、擅长品类整批删了重插
        supplierMapper.deleteCategories(id);
        insertCategories(id, categories);
        supplierMapper.deleteGoodAt(id);
        insertGoodAt(id, goodAt);

        // 4. 操作记录：直接生效的关键字段写进"修改"的备注；挂起的另写一条"提交改动"
        docLogService.add(BizType.SUPPLIER, id, "update", applied.isEmpty() ? null : views.describe(applied));
        if (!pending.isEmpty()) {
            docLogService.add(BizType.SUPPLIER, id, "change", views.describe(pending));
        }
        // 5. 工厂仓：含成衣厂、加工厂的没有就建；不含的不建，但有仓（以前是工厂、分类去掉了）照样跟着简称改名
        warehouseService.syncFactoryWarehouse(id, current.getCode(), target.getShortName(), SupplierDicts.hasFactory(categories));
        boolean showAccount = SupplierPerms.has(SupplierPerms.BANK);
        return new SupplierSaveResult(id, views.display(applied, showAccount), views.display(pending, showAccount));
    }

    /** 关键字段的条件更新：0 行说明这期间状态变了（被拉黑、被别人补了信用代码）→ 1002 */
    private void writeKeys(LambdaUpdateWrapper<Supplier> wrapper) {
        if (supplierMapper.update(new Supplier(), wrapper) == 0) {
            throw new BizException(ErrorCodes.STATE_CHANGED, "状态已变化，请刷新后再试");
        }
    }

    /** 挂起一份改动（RFC 004 §4 第 4 步）：已经有一份待批的 → 1000；先查一次，部分唯一索引 uk_sup_change_pending 兜底 */
    private void propose(long id, List<ChangeItem> items) {
        String exists = "已经有一份待批的改动，先撤回或者等批完再改关键字段";
        if (changeMapper.exists(new LambdaQueryWrapper<SupplierChange>()
                .eq(SupplierChange::getSupplierId, id).eq(SupplierChange::getStatus, ChangeStatus.PENDING))) {
            throw new BizException(exists);
        }
        SupplierChange change = new SupplierChange();
        change.setSupplierId(id);
        change.setChanges(items);
        change.setStatus(ChangeStatus.PENDING);
        change.setApplyBy(LoginUser.currentUserId());
        change.setApplyTime(OffsetDateTime.now(clock));
        try {
            changeMapper.insert(change);
        } catch (DuplicateKeyException e) {
            throw new BizException(exists);
        }
    }

    /** 普通字段：请求里的值规整后放进实体（空串当空） */
    private static void applyNormal(Supplier s, SupplierSaveReq req) {
        s.setShortName(trim(req.getShortName()));
        s.setLevel(trim(req.getLevel()));
        s.setContact(trim(req.getContact()));
        s.setPhone(trim(req.getPhone()));
        s.setWechat(trim(req.getWechat()));
        s.setAddress(trim(req.getAddress()));
        s.setBuyerId(req.getBuyerId());
        s.setRemark(trim(req.getRemark()));
    }

    /**
     * 关键字段：请求里的值规整后放进实体。账号没有 bank 权限的不收、和现在遮过的样子一样的当没改（RFC 004 §5）。
     * current 是改之前的，新建时是 null
     */
    private static void applyKey(Supplier s, SupplierSaveReq req, Supplier current) {
        s.setName(trim(req.getName()));
        s.setCreditCode(CreditCode.normalize(req.getCreditCode()));
        s.setSettleMethod(trim(req.getSettleMethod()));
        s.setCreditDays(req.getCreditDays());
        s.setInvoiceType(trim(req.getInvoiceType()));
        s.setTaxPct(req.getTaxPct());
        s.setBankName(trim(req.getBankName()));
        s.setBankAccountName(trim(req.getBankAccountName()));
        String currentAccount = current == null ? null : current.getBankAccount();
        String account = BankAccounts.normalize(req.getBankAccount());
        if (!SupplierPerms.has(SupplierPerms.BANK)
                || account != null && currentAccount != null && account.equals(BankAccounts.mask(currentAccount))) {
            account = currentAccount;
        }
        s.setBankAccount(account);
    }

    /** 保存前的检查：信用代码格式和唯一、字典值、新选的擅长品类是不是启用的。current 新建时是 null */
    private void validate(Supplier target, Supplier current, List<String> categories, List<String> currentCategories,
                          List<Long> goodAt, List<Long> currentGoodAt) {
        String creditBefore = current == null ? null : current.getCreditCode();
        if (!Objects.equals(target.getCreditCode(), creditBefore)) {
            String problem = CreditCode.problem(target.getCreditCode());
            if (problem != null) {
                throw new BizException(problem);
            }
            SupplierRules.ensureCreditUnique(supplierMapper, target.getCreditCode(), current == null ? null : current.getId());
        }
        dicts.check(SupplierDicts.SETTLE_METHOD, target.getSettleMethod(),
                current == null ? null : current.getSettleMethod(), "结算方式");
        dicts.check(SupplierDicts.INVOICE_TYPE, target.getInvoiceType(),
                current == null ? null : current.getInvoiceType(), "发票类型");
        dicts.checkAll(SupplierDicts.SUPPLIER_CATEGORY, categories, currentCategories, "分类");
        Set<Long> added = new LinkedHashSet<>(goodAt);
        currentGoodAt.forEach(added::remove);
        goodAtCategories.checkEnabled(added);
    }

    private void insertCategories(long id, List<String> categories) {
        if (!categories.isEmpty()) {
            supplierMapper.insertCategories(categories.stream().map(c -> new SupplierCategoryRow(id, c)).toList());
        }
    }

    private void insertGoodAt(long id, List<Long> categoryIds) {
        if (!categoryIds.isEmpty()) {
            supplierMapper.insertGoodAt(categoryIds.stream().map(c -> new SupplierGoodAtRow(id, c)).toList());
        }
    }

    @Override
    @Transactional
    public void delete(long id) {
        Supplier s = lock(id);
        String why = SupplierRules.UNDELETABLE.get(s.getStatus());
        if (why != null) {
            throw new BizException(why);
        }
        // 先删本域的子表，再删挂在它上面的附件、操作记录，最后删自己（RFC 003 §2）。
        // 被价格档案、物料、单据引用的，删自己那一句被外键拦住，整个回滚，返回 1005
        supplierMapper.deleteCategories(id);
        supplierMapper.deleteGoodAt(id);
        certMapper.delete(new LambdaQueryWrapper<SupplierCert>().eq(SupplierCert::getSupplierId, id));
        changeMapper.delete(new LambdaQueryWrapper<SupplierChange>().eq(SupplierChange::getSupplierId, id));
        warehouseService.deleteFactoryWarehouse(id);
        attachmentService.deleteByDoc(BizType.SUPPLIER, id);
        docLogService.deleteByDoc(BizType.SUPPLIER, id);
        supplierMapper.deleteById(id);
    }

    // ------------------------------------------------------------------ 状态动作

    @Override
    @Transactional
    public void submit(long id) {
        // 锁住再查资质：资质增删先 FOR SHARE 锁这一行，申请准入的时候不会有人正在删营业执照（RFC 004 §2.2）
        Supplier s = lock(id);
        SupplierStatus to = FLOW.next("submit", s.getStatus());
        List<String> missing = missingForAdmit(s);
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCodes.BIZ_ERROR,
                    "还缺 " + missing.size() + " 项资料，补齐了再申请准入：" + String.join("、", missing), missing);
        }
        StatusCas.update(supplierMapper, new Supplier(),
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)
                        .set(Supplier::getAdmitApplyBy, LoginUser.currentUserId())
                        .set(Supplier::getAdmitApplyTime, OffsetDateTime.now(clock)));
        docLogService.add(BizType.SUPPLIER, id, "submit", s.getStatus(), to, null);
    }

    /** 申请准入缺哪些（RFC 004 §2.1）：一次列全 */
    List<String> missingForAdmit(Supplier s) {
        List<String> missing = new ArrayList<>();
        if (s.getCreditCode() == null) {
            missing.add("统一社会信用代码");
        }
        boolean license = certMapper.exists(new LambdaQueryWrapper<SupplierCert>()
                .eq(SupplierCert::getSupplierId, s.getId())
                .eq(SupplierCert::getCertType, SupplierDicts.LICENSE)
                .and(w -> w.isNull(SupplierCert::getValidUntil).or().ge(SupplierCert::getValidUntil, LocalDate.now(clock))));
        if (!license) {
            missing.add("营业执照（资质里上传，没过期的）");
        }
        if (!StringUtils.hasText(s.getContact())) {
            missing.add("联系人");
        }
        if (!StringUtils.hasText(s.getBankName())) {
            missing.add("开户行");
        }
        if (!StringUtils.hasText(s.getBankAccount())) {
            missing.add("账号");
        }
        return missing;
    }

    @Override
    @Transactional
    public void withdraw(long id) {
        Supplier s = getEntity(id);
        SupplierStatus to = FLOW.next("withdraw", s.getStatus());
        if (!Objects.equals(s.getAdmitApplyBy(), LoginUser.currentUserId())) {
            throw new BizException("只有申请人能撤回");
        }
        StatusCas.update(supplierMapper, new Supplier(), clearApply(
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)));
        docLogService.add(BizType.SUPPLIER, id, "withdraw", s.getStatus(), to, null);
    }

    @Override
    @Transactional
    public void audit(long id) {
        Supplier s = getEntity(id);
        SupplierStatus to = FLOW.next("audit", s.getStatus());
        ensureNotApplicant(s);
        StatusCas.update(supplierMapper, new Supplier(),
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)
                        .set(Supplier::getAdmitBy, LoginUser.currentUserId())
                        .set(Supplier::getAdmitTime, OffsetDateTime.now(clock)));
        docLogService.add(BizType.SUPPLIER, id, "audit", s.getStatus(), to, null);
    }

    @Override
    @Transactional
    public void reject(long id, String reason) {
        Supplier s = getEntity(id);
        SupplierStatus to = FLOW.next("reject", s.getStatus());
        ensureNotApplicant(s);
        StatusCas.update(supplierMapper, new Supplier(), clearApply(
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)));
        docLogService.add(BizType.SUPPLIER, id, "reject", s.getStatus(), to, reason);
    }

    @Override
    @Transactional
    public void disable(long id) {
        simple(id, "disable");
    }

    @Override
    @Transactional
    public void enable(long id) {
        simple(id, "enable");
    }

    @Override
    @Transactional
    public void blacklist(long id, String reason) {
        Supplier s = getEntity(id);
        SupplierStatus to = FLOW.next("blacklist", s.getStatus());
        StatusCas.update(supplierMapper, new Supplier(), clearApply(
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)
                        .set(Supplier::getBlackReason, reason)
                        .set(Supplier::getBlackBy, LoginUser.currentUserId())
                        .set(Supplier::getBlackTime, OffsetDateTime.now(clock))));
        docLogService.add(BizType.SUPPLIER, id, "blacklist", s.getStatus(), to, reason);
    }

    @Override
    @Transactional
    public void unblacklist(long id, String reason) {
        Supplier s = getEntity(id);
        SupplierStatus target = s.getAdmitTime() != null ? DISABLED : PENDING_ADMIT;
        SupplierStatus to = FLOW.next("unblacklist", s.getStatus(), target);
        StatusCas.update(supplierMapper, new Supplier(),
                StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to)
                        .setSql("black_reason = NULL").setSql("black_by = NULL").setSql("black_time = NULL"));
        docLogService.add(BizType.SUPPLIER, id, "unblacklist", s.getStatus(), to, reason);
    }

    /** 不带别的列的状态动作：停用、启用 */
    private void simple(long id, String action) {
        Supplier s = getEntity(id);
        SupplierStatus to = FLOW.next(action, s.getStatus());
        StatusCas.update(supplierMapper, new Supplier(), StatusCas.of(Supplier::getId, id, Supplier::getStatus, s.getStatus(), to));
        docLogService.add(BizType.SUPPLIER, id, action, s.getStatus(), to, null);
    }

    /** 准入申请结束了（撤回、驳回、拉黑）：申请人、申请时间清掉，"待我审批"就不再算它 */
    private static LambdaUpdateWrapper<Supplier> clearApply(LambdaUpdateWrapper<Supplier> wrapper) {
        return wrapper.setSql("admit_apply_by = NULL").setSql("admit_apply_time = NULL");
    }

    /** 不能自己批自己的（架构 §4.6），超级管理员也一样 */
    private static void ensureNotApplicant(Supplier s) {
        if (Objects.equals(s.getAdmitApplyBy(), LoginUser.currentUserId())) {
            throw new BizException("准入申请是你提的，要另一个有准入权限的人来批");
        }
    }

    // ------------------------------------------------------------------ 给别的页面、别的域

    @Override
    public List<SupplierOption> options(List<String> categories, Usage usage, String keyword, Long includeId) {
        Set<SupplierStatus> statuses = SupplierRules.USABLE.get(usage == null ? Usage.SAMPLE : usage);
        QueryWrapper<Supplier> wrapper = new QueryWrapper<Supplier>()
                .select("id", "code", "short_name", "status")
                .in("status", statuses.stream().map(Enum::name).toList());
        List<String> wanted = categories == null ? List.of() : categories.stream().filter(StringUtils::hasText).toList();
        if (!wanted.isEmpty()) {
            SupplierLists.inCategory(wrapper, wanted);
        }
        if (StringUtils.hasText(keyword)) {
            String like = SupplierLists.likePattern(keyword);
            List<String> codes = Keywords.codes(keyword);
            wrapper.and(w -> {
                w.apply("upper(code) LIKE {0}", Keywords.prefix(keyword)).or().apply("name ILIKE {0}", like)
                        .or().apply("short_name ILIKE {0}", like);
                if (codes != null) w.or().in("upper(code)", codes);
            });
        }
        wrapper.orderByAsc("code");
        List<Supplier> found = new ArrayList<>(supplierMapper.selectPage(new Page<>(1, OPTIONS_LIMIT, false), wrapper).getRecords());
        if (includeId != null && found.stream().noneMatch(s -> s.getId().equals(includeId))) {
            Supplier included = supplierMapper.selectById(includeId);
            if (included != null) {
                found.addFirst(included);
            }
        }
        return found.stream().map(s -> new SupplierOption(s.getId(), s.getCode(), s.getShortName(), s.getStatus())).toList();
    }

    @Override
    public SupplierRef getRef(long id) {
        return toRef(getEntity(id));
    }

    @Override
    public Map<Long, SupplierRef> refs(Collection<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return new HashMap<>();                 // 调用方可能拿 null 的 id 来 get，不给 Map.of()
        }
        return supplierMapper.selectByIds(wanted).stream().collect(Collectors.toMap(Supplier::getId, SupplierServiceImpl::toRef));
    }

    private static SupplierRef toRef(Supplier s) {
        return new SupplierRef(s.getId(), s.getCode(), s.getName(), s.getShortName(), s.getStatus(),
                s.getTaxPct(), s.getSettleMethod(), s.getCreditDays(), s.getInvoiceType(), s.getBuyerId(), s.getContact(), s.getPhone());
    }

    @Override
    public void checkUsable(long id, Usage usage) {
        Supplier s = getEntity(id);
        if (!SupplierRules.USABLE.get(usage).contains(s.getStatus())) {
            String what = usage == Usage.BULK ? "只有合作中的供应商能下大货单" : "不能用来打样、录价格";
            throw new BizException(s.getShortName() + " 现在是「" + s.getStatus().getLabel() + "」，" + what);
        }
    }

    @Override
    public Map<Long, List<String>> categories(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> out = new HashMap<>();
        supplierMapper.selectByIds(ids).forEach(s -> out.put(s.getId(), new ArrayList<>()));
        supplierMapper.selectCategoriesOf(ids).forEach(r -> out.computeIfAbsent(r.getSupplierId(), k -> new ArrayList<>())
                .add(r.getCategory()));
        return out;
    }

    // ------------------------------------------------------------------ 小工具

    Supplier getEntity(long id) {
        Supplier s = supplierMapper.selectById(id);
        if (s == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return s;
    }

    private Supplier lock(long id) {
        Supplier s = supplierMapper.selectByIdForUpdate(id);
        if (s == null) {
            throw new BizException(404, NOT_FOUND);
        }
        return s;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static <T> List<T> distinct(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).distinct().toList();
    }
}
