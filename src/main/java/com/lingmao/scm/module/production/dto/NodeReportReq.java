package com.lingmao.scm.module.production.dto;

import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.production.entity.NodeReportAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 报进度（电脑、手机共用，前端 NodeReportForm）。START：actualStart 必填、actualFinish 不发；
 * FINISH：actualFinish 不发 = 今天，actualStart 不发 = 节点原来的（没有就等于完成日期）
 */
@Data
public class NodeReportReq {
    @NotNull(message = "请选只报开始还是报完成")
    private NodeReportAction action;
    private LocalDate actualStart;
    private LocalDate actualFinish;
    @Min(value = 0, message = "数量不能是负数")
    private Integer doneQty;
    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
    @Valid
    @Size(max = 9, message = "照片最多 9 张")
    private List<AttachmentReq> photos = List.of();
}
