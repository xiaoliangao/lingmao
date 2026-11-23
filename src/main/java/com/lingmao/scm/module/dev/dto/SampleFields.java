package com.lingmao.scm.module.dev.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Public editable sample fields only. Status/tenant/audit/conversion fields never bind from a request. */
@Data
public class SampleFields {
    @NotNull(message="请选择主体公司") @Positive(message="主体公司编号不合法") private Long companyId;
    @NotBlank(message="样衣名称不能为空") @Size(max=100,message="样衣名称最长100个字") private String name;
    @NotBlank(message="请选择品牌") @Size(max=64,message="品牌最长64个字") private String brand;
    @NotNull(message="请选择品类") @Positive(message="品类编号不合法") private Long categoryId;
    @NotNull(message="请填写年份") @Min(value=2000,message="年份须在2000–2099") @Max(value=2099,message="年份须在2000–2099") private Integer year;
    @NotBlank(message="请选择季节") @Size(max=64,message="季节最长64个字") private String season;
    @Size(max=64,message="波段最长64个字") private String wave;
    @Positive(message="设计师编号不合法") private Long designerId;
    @Positive(message="版师编号不合法") private Long patternMakerId;
    @NotBlank(message="请选择来源") @Size(max=64,message="来源最长64个字") private String source;
    @Positive(message="客户编号不合法") private Long customerId;
    @Size(max=64,message="客户款号最长64个字") private String customerStyleNo;
    @NotNull(message="请选择尺码组") @Positive(message="尺码组编号不合法") private Long sizeGroupId;
    @NotNull(message="请选择颜色") @Size(min=1,max=100,message="请选择1–100个颜色") private List<@NotNull(message="颜色编号不能为空") @Positive(message="颜色编号不合法") Long> colorIds=new ArrayList<>();
    @Positive(message="打样工厂编号不合法") private Long sampleFactoryId;
    @Positive(message="面料供应商编号不合法") private Long fabricSupplierId;
    @Size(max=255,message="面料描述最长255个字") private String fabricDesc;
    private LocalDate expectFinishDate;
    @Pattern(regexp="^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,2})?$",message="预估吊牌价须为非负十进制字符串，最多2位小数") private String estTagPrice;
    @Pattern(regexp="^(0|[1-9][0-9]{0,9})(\\.[0-9]{1,4})?$",message="预估成本须为非负十进制字符串，最多4位小数") private String estCost;
    @Size(max=500,message="备注最长500个字") private String remark;
}
