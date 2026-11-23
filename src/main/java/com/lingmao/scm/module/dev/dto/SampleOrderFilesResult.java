package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import java.util.List;
public record SampleOrderFilesResult(Long sampleId,Long companyId,int sampleVersionLock,Long orderId,int version,
    int sampleVersion,SampleStatus sampleStatus,SampleOrderStatus orderStatus,String warning,List<AttachmentVO> files) {
    public static SampleOrderFilesResult of(SampleActionResult a,List<AttachmentVO> files) {
        return new SampleOrderFilesResult(a.sampleId(),a.companyId(),a.sampleVersionLock(),a.orderId(),a.version(),a.sampleVersion(),a.sampleStatus(),a.orderStatus(),a.warning(),files);
    }
}
