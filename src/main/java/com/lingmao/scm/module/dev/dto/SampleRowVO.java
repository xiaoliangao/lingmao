package com.lingmao.scm.module.dev.dto;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.dev.entity.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;
@Data
public class SampleRowVO {
    private Long id,companyId,categoryId,designerId,patternMakerId,convertedStyleId,currentOrderId,factoryId,makerId;
    private String code,name,brand,categoryName,season,wave,designerName,patternMakerName,source,fabricDesc,estTagPrice,estCost,showStatus,convertedStyleCode,currentOrderNo,factoryName,makerName;
    private int version,year,currentOrderVersion,currentSampleVersion,qty;
    private AttachmentVO image;
    private SampleStatus status;
    private SampleType sampleType;
    private SampleOrderStatus orderStatus;
    private MakeWay makeWay;
    private LocalDate needDate;
    private SampleCurrentNode currentNode;
    private boolean late;
    private OffsetDateTime createTime;
}
