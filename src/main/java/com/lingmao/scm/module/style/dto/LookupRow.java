package com.lingmao.scm.module.style.dto;

import lombok.Data;

/**
 * 导入导出时按编码找别的域的东西（主力面料、默认加工厂）的一行：id、编码、名称（供应商是简称）。
 * StyleMapper.xml 只读查 bas_material、sup_supplier（架构 §3.2 规则 4）；状态这类业务判断照样调对方的 Service
 */
@Data
public class LookupRow {
    private Long id;
    private String code;
    private String name;
}
