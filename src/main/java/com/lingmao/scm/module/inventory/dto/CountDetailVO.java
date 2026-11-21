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
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime bookTime;
    private int itemCount;
    private int filledCount;
    private int gainCount;
    private int lossCount;
    /** 待审核时：取账面数以后这个仓库、这些 SKU 有几笔进出（审核确认框提示）；别的状态是 0 */
    private int movesSinceBook;
    /** 过过账（审核过又反审核回来的草稿）：不能删，只能作废 */
    private boolean posted;
    /** 附件能不能改：在本人数据范围里、有 :edit 权限（只靠"审核不受数据范围限制"看到的单不能改），不看状态 */
    private boolean filesEditable;
    private List<CountItemVO> items;
}
