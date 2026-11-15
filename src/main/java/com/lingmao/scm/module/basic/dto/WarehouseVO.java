package com.lingmao.scm.module.basic.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;

/** 仓库列表、详情：带绑定工厂的简称（只读 JOIN sup_supplier，架构 §3.2 规则 4）和负责人昵称 */
@Data
public class WarehouseVO {
    private Long id;
    private String code;
    private String name;
    private String type;
    private Long factoryId;
    /** 绑定工厂的简称 */
    private String factoryName;
    private String address;
    private Long keeperId;
    private String keeperName;
    private String phone;
    private Integer status;
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
