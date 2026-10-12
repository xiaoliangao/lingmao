package com.lingmao.scm.module.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;

/** 操作日志。只追加不修改，没有审计字段和逻辑删除，所以不继承 BaseEntity */
@Data
@TableName("sys_oper_log")
public class SysOperLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String module;
    private String action;
    private String method;
    private String requestMethod;
    private String requestUri;
    private String params;
    private Boolean success;
    private String errorMsg;
    private Integer costMs;
    private String ip;

    /** 不赋值，插入时用表的默认值 now() */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;
}
