package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.dev.entity.*;
import java.time.LocalDate;
import java.util.List;
public record SampleOrderVO(Long id,Long sampleId,Long companyId,String no,int version,int sampleVersionLock,
    int sampleVersion,SampleType sampleType,boolean current,SampleOrderStatus status,MakeWay makeWay,
    Long factoryId,String factoryName,Long makerId,String makerName,LocalDate needDate,int qty,Long sizeGroupId,
    List<ColorOption> colors,List<String> sizeCodes,String reason,String requirement,List<SampleNodeVO> nodes,
    SampleReviewVO review,Actions actions) {
    public record Actions(boolean edit,boolean dispatch,boolean node,boolean review) { }
}
