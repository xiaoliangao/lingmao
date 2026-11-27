package com.lingmao.scm.module.production.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;

/** 工厂产能列表的一行：某家厂、某个品类在看的这个月用的产能，和这个月交期的单占了多少 */
@Data
public class CapacityRowVO {
    private Long id;
    private Long factoryId;
    private String factoryCode;
    private String factoryShortName;
    /** null = 不分品类 */
    private Long categoryId;
    /** 品类，带上级："女装 / 外套"；不分品类是 null */
    private String categoryName;
    /** 这条从哪个月起，yyyy-MM；和看的月份不一样就是沿用前面登记的 */
    private String startMonth;
    private int dailyQty;
    /** 看的这个月一共能做多少件：日产能 × 这个月的天数 */
    private long monthCapacity;
    /** 看的这个月交期的单还要做多少件（没作废、没完成、没结案的，草稿也算；数量 − 良品） */
    private long monthLoad;
    private String remark;
    private String updateByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
