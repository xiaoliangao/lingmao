package com.lingmao.scm.module.dev.service.impl;
import com.lingmao.scm.module.biz.dto.DocRef;
import com.lingmao.scm.module.biz.dto.StatusLabels;
import com.lingmao.scm.module.biz.dto.StatusTone;
import com.lingmao.scm.module.biz.entity.BizType;
import com.lingmao.scm.module.dev.entity.*;
final class SampleReferences {
    private SampleReferences(){}
    static final StatusLabels<SampleStatus> SAMPLES=StatusLabels.of(SampleStatus.class)
        .with(SampleStatus.DEVELOPING,"开发中",StatusTone.ACTIVE).with(SampleStatus.PASSED,"已通过",StatusTone.DONE)
        .with(SampleStatus.CONVERTED,"已转大货",StatusTone.DONE).with(SampleStatus.ELIMINATED,"已淘汰",StatusTone.VOID);
    static final StatusLabels<SampleOrderStatus> ORDERS=StatusLabels.of(SampleOrderStatus.class)
        .with(SampleOrderStatus.WAIT_ASSIGN,"待派单",StatusTone.OFF).with(SampleOrderStatus.MAKING,"打样中",StatusTone.ACTIVE)
        .with(SampleOrderStatus.WAIT_REVIEW,"待评审",StatusTone.PENDING).with(SampleOrderStatus.PASSED,"已通过",StatusTone.DONE)
        .with(SampleOrderStatus.REVISE,"需改版",StatusTone.PENDING).with(SampleOrderStatus.ELIMINATED,"已淘汰",StatusTone.VOID)
        .with(SampleOrderStatus.CANCELLED,"已取消",StatusTone.VOID);
    static final StatusLabels<SampleReviewResult> REVIEWS=StatusLabels.of(SampleReviewResult.class)
        .with(SampleReviewResult.PASS,"通过",StatusTone.DONE).with(SampleReviewResult.REVISE,"改版",StatusTone.PENDING)
        .with(SampleReviewResult.ELIMINATE,"淘汰",StatusTone.VOID);
    static DocRef of(Sample s){return DocRef.of(BizType.SAMPLE,s.getId(),s.getCode(),s.getStatus(),SAMPLES,s.getDesignerId()).withCompany(s.getCompanyId());}
    static DocRef of(SampleOrder o){return DocRef.of(BizType.SAMPLE_ORDER,o.getId(),o.getNo(),o.getStatus(),ORDERS,o.getMakerId()).withParent(o.getSampleId()).withCompany(o.getCompanyId());}
}
