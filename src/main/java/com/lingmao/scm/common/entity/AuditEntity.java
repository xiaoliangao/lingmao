package com.lingmao.scm.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 实体的基类：主键 + 四个审计列（谁、什么时候建的，谁、什么时候最后改的），没有逻辑删除。
 * 新业务表（主数据、单据）一律继承它：用户 11-15 定，新表不用逻辑删除，没被引用的直接物理 DELETE，
 * 被引用的由外键（RESTRICT）拦住，GlobalExceptionHandler 把它翻成 1005"已被××引用，不能删除，只能停用"。
 * 已有的 sys_ 表沿用逻辑删除，继承 BaseEntity（它在这个类上加了 deleted）。
 * <p>
 * 字段怎么被填：id 由数据库 IDENTITY 生成；审计列由 AuditMetaObjectHandler 在插入、更新时自动填。
 * 创建人、创建时间标了 updateStrategy = NEVER：更新永远不写这两列（"先查出来、改、整条写回去"时也不会碰它们）。
 * 时间类型为什么是 OffsetDateTime：表里的列是 TIMESTAMPTZ（带时区的时间点），PostgreSQL 驱动只把它映射成
 * OffsetDateTime，用 LocalDateTime 读会报 "Cannot convert the column of type TIMESTAMPTZ"。
 * 项目约定：所有时间列用 TIMESTAMPTZ，Java 一律 OffsetDateTime，JSON 统一按东八区格式化输出。
 */
@Data
public abstract class AuditEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private Long createBy;

    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime updateTime;
}
