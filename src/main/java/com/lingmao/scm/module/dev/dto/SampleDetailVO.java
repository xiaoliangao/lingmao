package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.basic.dto.ColorOption;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.dev.entity.SampleStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.OffsetDateTime;
import java.util.List;
@Data @EqualsAndHashCode(callSuper=true)
public class SampleDetailVO extends SampleFields {
    private Long id,convertedStyleId,convertBy,createBy;
    private String code,showStatus,categoryName,designerName,patternMakerName,sampleFactoryName,fabricSupplierName,baseSize,sizeGroupName,convertedStyleCode;
    private int version;
    private SampleStatus status;
    private List<ColorOption> colors;
    private List<String> sizes;
    private List<AttachmentVO> images;
    private SampleOrderVO currentOrder;
    private OffsetDateTime convertTime,createTime,updateTime;
    private Actions actions;
    public record Actions(boolean edit,boolean remove,boolean stop,boolean reopen,boolean convert) { }
}
