package com.lingmao.scm.module.basic.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改物料（PUT 整体覆盖），也是新建的公共部分（MaterialCreateReq 再加第一批 SKU）。
 * 分类、库存单位建好不改：修改时要和原来一样，不一样报 1000（RFC 003 §7.2）。
 * 采购单位不填 = 库存单位；换算系数不填 = 1；最小包装量不填 = 1；默认来源不填 = 自购
 */
@Data
public class MaterialSaveReq {

    @NotBlank(message = "名称不能为空")
    @Size(max = 200, message = "名称最长 200 个字")
    private String name;

    /** 启用的二级分类 */
    @NotNull(message = "分类不能为空")
    private Long categoryId;

    /** 字典 material_type */
    @NotBlank(message = "物料类型不能为空")
    @Size(max = 64, message = "物料类型最长 64")
    private String materialType;

    /** 字典 unit */
    @NotBlank(message = "库存单位不能为空")
    @Size(max = 64, message = "库存单位最长 64")
    private String stockUnit;

    @Size(max = 64, message = "采购单位最长 64")
    private String purchaseUnit;

    /** 1 采购单位 = n 库存单位 */
    @DecimalMin(value = "0", inclusive = false, message = "换算系数要大于 0")
    @Digits(integer = 8, fraction = 6, message = "换算系数最多 8 位整数、6 位小数")
    private BigDecimal conversionFactor;

    @DecimalMin(value = "0", message = "门幅不能是负数")
    @Digits(integer = 6, fraction = 2, message = "门幅最多 2 位小数")
    private BigDecimal widthCm;

    @DecimalMin(value = "0", message = "克重不能是负数")
    @Digits(integer = 6, fraction = 2, message = "克重最多 2 位小数")
    private BigDecimal weightGsm;

    @Size(max = 255, message = "成分最长 255 个字")
    private String composition;

    @Size(max = 64, message = "组织 / 纹理最长 64 个字")
    private String texture;

    /** 面料功能，如"防水、抗皱"；物料类型不是面料、里料时不存 */
    @Size(max = 255, message = "面料功能最长 255 个字")
    private String fabricFunction;

    /** 带不带功能性吊牌；null = 没填 */
    private Boolean withTag;

    /** 功能吊牌的名称，几个用顿号隔开；withTag 是 true 时必填，不是 true 时不存 */
    @Size(max = 255, message = "吊牌名称最长 255 个字")
    private String tagNames;

    /** 默认供应商：只当默认值，后端不查状态（架构 A23）。一期页面上先不给选（第 3 步接供应商下拉） */
    private Long defaultSupplierId;

    @Size(max = 64, message = "供应商货号最长 64 个字")
    private String supplierItemNo;

    /** 参考单价：按采购单位、含税 */
    @DecimalMin(value = "0", message = "参考单价不能是负数")
    @Digits(integer = 10, fraction = 4, message = "参考单价最多 4 位小数")
    private BigDecimal refPrice;

    /** 起订量，采购单位 */
    @DecimalMin(value = "0", message = "起订量不能是负数")
    @Digits(integer = 11, fraction = 3, message = "起订量最多 3 位小数")
    private BigDecimal moq;

    /** 最小包装量，采购单位 */
    @DecimalMin(value = "0", inclusive = false, message = "最小包装量要大于 0")
    @Digits(integer = 11, fraction = 3, message = "最小包装量最多 3 位小数")
    private BigDecimal minPackQty;

    @Min(value = 0, message = "采购周期不能是负数")
    @Max(value = 3650, message = "采购周期最多 3650 天")
    private Integer leadDays;

    @Min(value = 1900, message = "年份不对")
    @Max(value = 2999, message = "年份不对")
    private Integer year;

    /** 字典 season */
    @Size(max = 64, message = "季节最长 64")
    private String season;

    /** MaterialSource：PURCHASE 自购 / FACTORY 厂购 / CUSTOMER_SUPPLIED 客供（TRADE 关时拒收） */
    @Pattern(regexp = "^(PURCHASE|FACTORY|CUSTOMER_SUPPLIED)$", message = "默认来源只能是 自购、厂购、客供")
    private String defaultSource;

    @DecimalMin(value = "0", message = "默认损耗不能是负数")
    @DecimalMax(value = "100", message = "默认损耗最多 100%")
    @Digits(integer = 3, fraction = 2, message = "默认损耗最多 2 位小数")
    private BigDecimal defaultLossPct;

    @DecimalMin(value = "0", message = "备料比不能是负数")
    @DecimalMax(value = "100", message = "备料比最多 100%")
    @Digits(integer = 3, fraction = 2, message = "备料比最多 2 位小数")
    private BigDecimal reservePct;

    @Size(max = 500, message = "备注最长 500 个字")
    private String remark;
}
