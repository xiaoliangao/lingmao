package com.lingmao.scm.module.order.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.order.entity.ChangeType;
import com.lingmao.scm.module.order.entity.ResponsibleParty;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** 变更单列表的一行（前端 OrderChangeRow）。summary、人名、按钮由 Service 填 */
@Data
public class OrderChangeRowVO {
    private Long id;
    private String no;
    private String status;
    private String rejectReason;
    private Long orderId;
    private String orderNo;
    private List<ChangeType> changeTypes = List.of();
    /** 库里逗号分隔的变更类型，转成 changeTypes 用，不返回 */
    @JsonIgnore
    private String changeTypesText;
    private String summary;
    private String reason;
    private ResponsibleParty responsibleParty;
    private String feeNote;
    @JsonIgnore
    private Long createBy;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    @JsonIgnore
    private Long auditBy;
    private String auditByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime auditTime;
    private List<String> actions = List.of();
}
