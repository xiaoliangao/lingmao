package com.lingmao.scm.module.supplier.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.supplier.entity.Supplier;
import com.lingmao.scm.module.supplier.entity.SupplierStatus;
import com.lingmao.scm.module.supplier.mapper.SupplierMapper;
import com.lingmao.scm.module.supplier.service.SupplierService.Usage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ACTIVE;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.ADMITTING;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.BLACKLISTED;
import static com.lingmao.scm.module.supplier.entity.SupplierStatus.PENDING_ADMIT;

/**
 * 各状态下能做什么，除了状态转换（那张表是 SupplierServiceImpl.FLOW）以外的几条，都写在这里一处（RFC 004 §2.2、§7、§10），
 * 供应商、改动、资质三个 Service 一起用，不各写各的 if。
 */
final class SupplierRules {

    /** 能改资料、能增删资质的状态：准入审批中的什么都不能改（RFC 004 §2.2） */
    static final Set<SupplierStatus> EDITABLE = Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(ADMITTING)));

    /** 关键字段直接改、不用审批的状态：只有待准入（RFC 004 §2.2）。其余能改的状态（合作中、停用、黑名单）挂起等审批 */
    static final Set<SupplierStatus> KEY_DIRECT = Collections.unmodifiableSet(EnumSet.of(PENDING_ADMIT));

    /** 不能删的状态和原因（RFC 004 §10） */
    static final Map<SupplierStatus, String> UNDELETABLE;

    /** 选进单据时，各用途接受哪些状态（PRD §6.8.2、RFC 004 §7） */
    static final Map<Usage, Set<SupplierStatus>> USABLE;

    static final String ADMITTING_LOCKED = "准入审批中不能改，先撤回";

    static {
        Map<SupplierStatus, String> undeletable = new EnumMap<>(SupplierStatus.class);
        undeletable.put(ADMITTING, "准入审批中的不能删，先撤回");
        undeletable.put(BLACKLISTED, "黑名单里的不能删：留着它，它的信用代码一直占着，换个名字重建也会被拦住");
        UNDELETABLE = Collections.unmodifiableMap(undeletable);

        Map<Usage, Set<SupplierStatus>> usable = new EnumMap<>(Usage.class);
        usable.put(Usage.SAMPLE, Collections.unmodifiableSet(EnumSet.of(PENDING_ADMIT, ADMITTING, ACTIVE)));
        usable.put(Usage.BULK, Collections.unmodifiableSet(EnumSet.of(ACTIVE)));
        USABLE = Collections.unmodifiableMap(usable);
    }

    private SupplierRules() {
    }

    /** 准入审批中 → 1000"准入审批中不能改，先撤回" */
    static void ensureEditable(SupplierStatus status) {
        if (!EDITABLE.contains(status)) {
            throw new BizException(ADMITTING_LOCKED);
        }
    }

    /**
     * 信用代码全表唯一（RFC 004 §4.1）：先查一次，重复时说清和哪家重复；查完到写入之间别人抢先了，由唯一索引兜底。
     * excludeId 是自己（修改时）
     */
    static void ensureCreditUnique(SupplierMapper mapper, String creditCode, Long excludeId) {
        if (creditCode == null) {
            return;
        }
        Supplier other = mapper.selectOne(new LambdaQueryWrapper<Supplier>()
                .select(Supplier::getId, Supplier::getCode, Supplier::getShortName)
                .eq(Supplier::getCreditCode, creditCode)
                .ne(excludeId != null, Supplier::getId, excludeId)
                .last("LIMIT 1"));
        if (other != null) {
            throw new BizException("统一社会信用代码和 " + other.getCode() + " " + other.getShortName() + " 重复");
        }
    }
}
