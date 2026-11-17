package com.lingmao.scm.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.biz.dto.DocHeaderVO;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 盘点单详情（前端 CountDetail） */
@Data
@EqualsAndHashCode(callSuper = true)
public class CountDetailVO extends DocHeaderVO {
    private Long warehouseId;
    private String warehouseName;
    private String warehouseType;
    private CountPurpose purpose;
    private CountScope scope;
    private String scopeText;
    private List<CountCategoryVO> categories;
    private LocalDate countDate;
    private Long counterId;
    private String counterName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime bookTime;
    private int itemCount;
    private int filledCount;
    private int gainCount;
    private int lossCount;
    /** 待审核时：取账面数以后这个仓库、这些 SKU 有几笔进出（审核确认框提示）；别的状态是 0 */
    private int movesSinceBook;
    /** 过过账（审核过又反审核回来的草稿）：不能删，只能作废 */
    private boolean posted;
    private List<CountItemVO> items;
}
