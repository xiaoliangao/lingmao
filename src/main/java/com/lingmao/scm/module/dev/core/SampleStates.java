package com.lingmao.scm.module.dev.core;

import com.lingmao.scm.common.doc.StatusFlow;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.exception.ErrorCodes;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleStatus;

import java.util.Set;

/** 两层动作状态机；Service 在同一事务中执行相应 CAS，数据库再检查提交时的真实组合。 */
public final class SampleStates {
    private static final Set<SampleOrderStatus> ACTIVE = Set.of(SampleOrderStatus.WAIT_ASSIGN,
            SampleOrderStatus.MAKING, SampleOrderStatus.WAIT_REVIEW);

    public static final StatusFlow<SampleStatus> SAMPLE = StatusFlow.of(SampleStatus.class)
            .on("pass", SampleStatus.DEVELOPING).to(SampleStatus.PASSED)
            .on("revise", SampleStatus.DEVELOPING).to(SampleStatus.DEVELOPING)
            .on("eliminate", SampleStatus.DEVELOPING).to(SampleStatus.ELIMINATED)
            .on("stop", SampleStatus.DEVELOPING).to(SampleStatus.ELIMINATED)
            .on("reopen", SampleStatus.PASSED, SampleStatus.ELIMINATED).to(SampleStatus.DEVELOPING)
            .on("convert", SampleStatus.PASSED).to(SampleStatus.CONVERTED)
            .build();

    public static final StatusFlow<SampleOrderStatus> ORDER = StatusFlow.of(SampleOrderStatus.class)
            .on("dispatch", SampleOrderStatus.WAIT_ASSIGN).to(SampleOrderStatus.MAKING)
            .on("send", SampleOrderStatus.MAKING, SampleOrderStatus.WAIT_REVIEW).to(SampleOrderStatus.WAIT_REVIEW)
            .on("unsend", SampleOrderStatus.MAKING, SampleOrderStatus.WAIT_REVIEW).to(SampleOrderStatus.MAKING)
            .on("pass", SampleOrderStatus.WAIT_REVIEW).to(SampleOrderStatus.PASSED)
            .on("revise", SampleOrderStatus.WAIT_REVIEW).to(SampleOrderStatus.REVISE)
            .on("eliminate", SampleOrderStatus.WAIT_REVIEW).to(SampleOrderStatus.ELIMINATED)
            .on("stop", SampleOrderStatus.WAIT_ASSIGN, SampleOrderStatus.MAKING, SampleOrderStatus.WAIT_REVIEW)
            .to(SampleOrderStatus.CANCELLED).build();

    private SampleStates() { }

    public static void requirePair(SampleStatus sample, SampleOrderStatus current) {
        boolean valid = sample != null && current != null && switch (sample) {
            case DEVELOPING -> ACTIVE.contains(current);
            case PASSED, CONVERTED -> current == SampleOrderStatus.PASSED;
            case ELIMINATED -> current == SampleOrderStatus.ELIMINATED || current == SampleOrderStatus.CANCELLED;
        };
        if (!valid) throw new BizException(ErrorCodes.STATE_CHANGED, "样衣和当前版状态不一致，请刷新后再试");
    }
}
