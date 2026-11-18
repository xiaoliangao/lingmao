package com.lingmao.scm.common.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

/**
 * 单据单头的基类（架构 §4.1、数据库设计 §1.2"单头公共列"）：审计列之外，再加单号、主体、审核人、审核时间、版本号、备注。
 * 需求订单、采购单、物料入库单……的单头实体继承它；明细不继承（明细没有审计列）。
 * <p>
 * 状态不在这里，各单据自己声明（大多是 DocStatus，算料单、资料包各有枚举），同样要标 NEVER：
 * <pre>
 * &#64;TableField(updateStrategy = FieldStrategy.NEVER)
 * private DocStatus status;
 * </pre>
 * 为什么不写成 DocEntity&lt;S&gt; 把状态也收进来：MyBatis-Plus 按字段的声明类型找类型处理器，泛型字段擦除成 Enum，存取会出错。
 * <p>
 * "由动作改"的列标 NEVER（架构 §4.3、RFC 001 §4.4）：保存草稿永远写不到它们，只有动作的条件更新（StatusCas 接着 .set）能改。
 * 不然一个人保存开着的旧页面，会把别人刚审核的审核人、刚加上去的版本号改回去。单号建好不改，也标 NEVER。
 * 版本号在库里默认 0，插入时不用管；保存草稿、每个状态动作都让它 +1（.setSql("version = version + 1")）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public abstract class DocEntity extends AuditEntity {

    /** 单号：前缀 + yyyyMMdd + 4 位流水（SerialNoGenerator.daily），建单时取 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private String no;

    /** 主体公司 bas_company.id：新建时页面默认填制单人的默认主体，必填；一期只记不隔离（PRD §5.7） */
    private Long companyId;

    /** 审核人；多级审批时是最后一级，各级见 biz_approval_step */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long auditBy;

    @TableField(updateStrategy = FieldStrategy.NEVER)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "Asia/Shanghai")
    private OffsetDateTime auditTime;

    /** 乐观锁（架构 §4.3）：打开时拿到，保存、动作时带回来比较 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Integer version;

    private String remark;
}
