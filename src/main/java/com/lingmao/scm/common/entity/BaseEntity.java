package com.lingmao.scm.common.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 带逻辑删除的实体：AuditEntity 的主键和四个审计列之外，多一个 deleted。只给沿用逻辑删除的 sys_ 表用（字典、用户、角色、菜单），
 * 新业务表不要继承它，继承 AuditEntity（为什么见那边的注释）。名字没改成别的，是为了已有的 sys 实体一行都不用动。
 * <p>
 * deleted 由 MyBatis-Plus 的逻辑删除机制接管：查询自动加 deleted = false，deleteById 变成 UPDATE ... SET deleted = true。
 * application.yaml 里全局配的 logic-delete-field: deleted 只对有 deleted 这个字段的实体生效，
 * 继承 AuditEntity 的实体没有这个字段，删除就是真的 DELETE（AuditEntityTest 钉住了这一点）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class BaseEntity extends AuditEntity {

    @TableLogic
    private Boolean deleted;
}
