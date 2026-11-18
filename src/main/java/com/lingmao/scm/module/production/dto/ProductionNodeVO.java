package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一个进度节点（前端 ProductionNode）：实际开始、实际完成、完成数量是最近一次报的；状态、晚几天现算 */
@Data
public class ProductionNodeVO {
    private Long id;
    private Integer seq;
    private String nodeCode;
    private String nodeName;
    private LocalDate planStart;
    private LocalDate planFinish;
    private LocalDate actualStart;
    private LocalDate actualFinish;
    private Integer doneQty;
    private String remark;
    private String state;
    private Integer lateDays;
    private String lastReportByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime lastReportTime;
    private int reportCount;
    /** 这个节点每次报工的照片，新的在前 */
    private List<AttachmentVO> photos = List.of();
}
