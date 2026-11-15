package com.lingmao.scm.module.style.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改款式（PUT 整体覆盖），也是新建的公共部分（StyleCreateReq 再加款号、颜色、图片、附件）。
 * 不收款号、状态（RFC 005 §7）；尺码组已经有 SKU 的不能换（1000）。字典列存 value，新选的要启用（RFC 005 §6）
 */
@Data
public class StyleSaveReq {

    @NotBlank(message = "款式名称不能为空")
    @Size(max = 100, message = "款式名称最长 100 个字")
    private String name;

    @Size(max = 100, message = "花名最长 100 个字")
    private String aliasName;

    /** 字典 style_class：款号开头的 2 位字母 */
    @NotBlank(message = "大类不能为空")
    @Size(max = 64, message = "大类最长 64")
    private String styleClass;

    /** 字典 brand */
    @NotBlank(message = "品牌不能为空")
    @Size(max = 64, message = "品牌最长 64")
    private String brand;

    /** 启用的叶子品类 */
    @NotNull(message = "品类不能为空")
    private Long categoryId;

    @NotNull(message = "年份不能为空")
    @Min(value = 2000, message = "年份要在 2000-2099 之间")
    @Max(value = 2099, message = "年份要在 2000-2099 之间")
    private Integer year;

    /** 字典 season */
    @NotBlank(message = "季节不能为空")
    @Size(max = 64, message = "季节最长 64")
    private String season;

    @Size(max = 64, message = "波段最长 64")
    private String wave;

    /** 风格，字典 style_tag */
    @Size(max = 64, message = "风格最长 64")
    private String styleTag;

    @Size(max = 64, message = "系列最长 64")
    private String series;

    @Size(max = 64, message = "性别最长 64")
    private String gender;

    /** 设计师：自动编号时必填，款号里用他的设计师编号 */
    private Long designerId;

    private Long patternMakerId;

    /** 字典 style_source，不填 = 自主开发（OWN）；客户来样 CUSTOMER_SAMPLE 只在 TRADE 开时收 */
    @Size(max = 64, message = "来源最长 64")
    private String source;

    /** TRADE 关时必须为空 */
    private Long customerId;

    @Size(max = 64, message = "客户款号最长 64 个字")
    private String customerStyleNo;

    @NotNull(message = "尺码组不能为空")
    private Long sizeGroupId;

    /** 主力面料：物料 id，只当默认值；新选的要启用 */
    private Long mainMaterialId;

    @Size(max = 50, message = "品名最长 50 个字")
    private String productName;

    @Size(max = 255, message = "成分最长 255 个字")
    private String composition;

    @Size(max = 64, message = "执行标准最长 64")
    private String execStandard;

    @Size(max = 64, message = "安全类别最长 64")
    private String safetyCategory;

    @DecimalMin(value = "0", message = "吊牌价不能是负数")
    @Digits(integer = 12, fraction = 2, message = "吊牌价最多 2 位小数")
    private BigDecimal tagPrice;

    @DecimalMin(value = "0", message = "参考成本价不能是负数")
    @Digits(integer = 10, fraction = 4, message = "参考成本价最多 4 位小数")
    private BigDecimal refCost;

    /** 默认加工厂：只当默认值，不查供应商状态（架构 A23） */
    private Long defaultFactoryId;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate launchDate;

    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
}
