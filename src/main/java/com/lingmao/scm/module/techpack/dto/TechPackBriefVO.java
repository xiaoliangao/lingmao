package com.lingmao.scm.module.techpack.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.lingmao.scm.module.techpack.entity.PackStage;
import com.lingmao.scm.module.techpack.entity.PackStatus;
import lombok.Data;

import java.time.OffsetDateTime;

/** 版本条上的一个版本（前端 TechPackBrief） */
@Data
public class TechPackBriefVO {
    private Long id;
    private PackStage stage;
    private Integer version;
    private PackStatus status;
    private String versionNote;
    private String publishByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime publishTime;
    private String createByName;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
