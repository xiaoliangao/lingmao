package com.lingmao.scm.module.inventory.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingmao.scm.module.inventory.entity.CountPurpose;
import com.lingmao.scm.module.inventory.entity.CountScope;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 盘点单列表的一行（前端 CountRow） */
@Data
public class CountRowVO {
    private Long id;
    private String no;
    private Long warehouseId;
    private String warehouseName;
    private CountPurpose purpose;
    private CountScope scope;
    /** "全部物料" / "分类：面料-梭织、辅料-拉链" / "手选 3 种" */
    private String scopeText;
    /** 按分类时选的分类名（"面料-梭织、辅料-拉链"），拼 scopeText 用，不返回 */
    @JsonIgnore
    private String categoryNames;
    private LocalDate countDate;
    private Long counterId;
    private String counterName;
    private int itemCount;
    private int filledCount;
    private int gainCount;
    private int lossCount;
    private String status;
    private Integer version;
    /** 制单人 id：算 actions（撤回只给制单人）用，不返回 */
    @JsonIgnore
    private Long createBy;
    private List<String> actions = List.of();
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
