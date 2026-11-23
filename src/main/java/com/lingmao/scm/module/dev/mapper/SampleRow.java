package com.lingmao.scm.module.dev.mapper;

import com.lingmao.scm.module.dev.entity.MakeWay;
import com.lingmao.scm.module.dev.entity.Sample;
import com.lingmao.scm.module.dev.entity.SampleOrderStatus;
import com.lingmao.scm.module.dev.entity.SampleType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/** SQL 一次分页得到主档、当前版与当前节点；不在列表 Service 逐行补查。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SampleRow extends Sample {
    private String categoryName;
    private String designerName;
    private String patternMakerName;
    private String convertedStyleCode;
    private Long currentOrderId;
    private String currentOrderNo;
    private Integer currentSampleVersion;
    private Integer currentOrderVersion;
    private SampleType sampleType;
    private SampleOrderStatus orderStatus;
    private MakeWay makeWay;
    private Long factoryId;
    private String factoryName;
    private Long makerId;
    private String makerName;
    private Integer qty;
    private LocalDate needDate;
    private Long currentNodeId;
    private String currentNodeCode;
    private String currentNodeName;
    private LocalDate currentNodePlanDate;
    private Long currentNodeOwnerId;
    private String currentNodeOwnerName;
    private Boolean late;
    private String showStatus;
    /** Home 真正最早待办排序日期，仅为 SQL 内部投影，不是用户可写字段。 */
    private LocalDate homeRankDate;
}
