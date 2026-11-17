package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 一张回货单（前端 ProductionReceipt）；actions 只有 void */
@Data
public class ProductionReceiptVO {
    private Long id;
    private String no;
    private String status;
    private LocalDate receiptDate;
    private Long warehouseId;
    private String warehouseName;
    private String logisticsNo;
    private String remark;
    private int qty;
    private int defectQty;
    private List<AttachmentVO> photos = List.of();
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    private String voidReason;
    private String voidByName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime voidTime;
    private List<ReceiptItemVO> items = List.of();
    private List<String> actions = List.of();
}
