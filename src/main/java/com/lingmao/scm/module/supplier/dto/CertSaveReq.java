package com.lingmao.scm.module.supplier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 加、改一份资质证照。文件先走上传接口（POST /api/files），这里存它返回的地址；签过名的地址也收，存之前去掉签名
 */
@Data
public class CertSaveReq {

    /** 字典 cert_type */
    @NotBlank(message = "请选证照类型")
    @Size(max = 64, message = "证照类型不对")
    private String certType;

    @NotBlank(message = "请上传文件")
    @Size(max = 512, message = "文件地址太长")
    private String fileUrl;

    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名最多 255 个字")
    private String fileName;

    /** 有效期至；空 = 长期 */
    private LocalDate validUntil;

    @Size(max = 255, message = "备注最多 255 个字")
    private String remark;
}
