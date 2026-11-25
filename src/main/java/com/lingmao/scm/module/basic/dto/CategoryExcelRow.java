package com.lingmao.scm.module.basic.dto;

import lombok.Data;

/**
 * 品类、物料分类导入的一行（RFC 018 §8.7）：领猫"基础档案 › 产品类目 / 物料类目"导出的列
 */
@Data
public class CategoryExcelRow {

    /** 分类名称：从一级写到这一级，用 > 隔开，如 服装（非通勤裤类）>裙子>半身裙 */
    private String path;

    /** 分类编码 */
    private String code;

    /** 状态：启用 / 停用 */
    private String status;

    /** 排序 */
    private Integer sort;
}
