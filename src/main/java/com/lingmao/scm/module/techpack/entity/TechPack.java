package com.lingmao.scm.module.techpack.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import com.lingmao.scm.common.mybatis.JsonbTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 资料包的一个版本（tp_tech_pack，数据库设计 §9.1）：一个款式一个阶段的一个版本，下面挂物料清单、核价的其他费用，工艺单文件挂在 biz_attachment。
 * 款式、阶段、版本号、来源只在插入时写；状态、生效人、生效时间、乐观锁由动作改（条件更新）。都标 NEVER，普通保存碰不到。
 * version 是版本号，所以乐观锁叫 versionLock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "tp_tech_pack", autoResultMap = true)
public class TechPack extends AuditEntity {

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long styleId;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private PackStage stage;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private PackStatus status;

    /** 本版说明，V2 起生效前必填 */
    private String versionNote;

    /** 转生产 BOM、修订时记下从哪一版复制来的 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long sourcePackId;

    /** 排版利用率 %，只记录 */
    private BigDecimal utilPct;


    private CostMode costMode;

    /** 成品单价，元/件（含料）；按成品采购算时用，没填时核价用价格档案里默认加工厂"包工包料"的现价 */
    private BigDecimal buyPrice;
    /** 加工费，元/件，按外发算时用；没填时核价用价格档案里默认加工厂清加工、再半包的现价（PRD §6.4.5） */
    private BigDecimal laborPrice;

    @TableField(typeHandler = JsonbTypeHandler.class, updateStrategy = FieldStrategy.NEVER)
    private PackOwnerSnapshot ownerSnapshot;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long publishBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime publishTime;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer versionLock;
}
