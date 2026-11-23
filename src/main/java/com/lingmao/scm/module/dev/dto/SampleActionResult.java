package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
/** A committed action receipt must not require a fresh detail read after an assignment changes scope. */
public record SampleActionResult(Long sampleId,Long companyId,int sampleVersionLock,Long orderId,int version,
    int sampleVersion,SampleStatus sampleStatus,SampleOrderStatus orderStatus,String warning) { }
