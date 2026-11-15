package com.lingmao.scm.module.supplier.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingmao.scm.common.entity.AuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 资质证照（sup_supplier_cert，数据库设计 §6.4）：营业执照、验厂报告……带有效期。
 * file_url 存不带签名的 /files/...，返回时签名；删证照只删这一行，文件留着（RFC 001 §10）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sup_supplier_cert")
public class SupplierCert extends AuditEntity {

    /** 挂在哪家供应商上，建好不换 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private Long supplierId;

    /** 字典 cert_type；LICENSE 营业执照申请准入时必须有 */
    private String certType;

    private String fileUrl;
    private String fileName;

    /** 有效期至；空 = 长期 */
    private LocalDate validUntil;

    private String remark;
}
