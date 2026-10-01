package com.lingmao.scm.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 所有业务表共有的 6 个字段，每个实体都继承它。
 * 为什么：审计字段（谁创建、谁修改、什么时候）和逻辑删除是每张表都要的，写一次就够。
 * 字段怎么被填：id 由数据库 IDENTITY 生成；时间由 AuditMetaObjectHandler 在插入/更新时自动填；
 * deleted 由 MyBatis-Plus 的逻辑删除机制接管——查询自动加 deleted = false，deleteById 变成 update。
 * 时间类型为什么是 OffsetDateTime：表里的列是 TIMESTAMPTZ（带时区的时间点），PostgreSQL 驱动只把它映射成
 * OffsetDateTime，用 LocalDateTime 读会报 "Cannot convert the column of type TIMESTAMPTZ"。
 * 项目约定：所有时间列用 TIMESTAMPTZ，Java 一律 OffsetDateTime，JSON 统一按东八区格式化输出。
 */
@Data
public abstract class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;

    @TableLogic
    private Boolean deleted;
}
