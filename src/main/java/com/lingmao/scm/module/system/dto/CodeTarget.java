package com.lingmao.scm.module.system.dto;

/**
 * 编码规则管的几种编码（RFC 018 §4.2）。scopeDict 是"按什么分"用的字典：款号按大类、物料编码按物料类型，供应商编码只有通用规则。
 * 单号、SKU 码、样衣编号不在这里（理由见 RFC 018 §4.2）
 */
public enum CodeTarget {
    STYLE("款号", "style_class", "大类"),
    MATERIAL("物料编码", "material_type", "物料类型"),
    SUPPLIER("供应商编码", null, null);

    /** 各表的编码列都是 VARCHAR(32)；sys_serial.seq_key 也是 32 */
    public static final int MAX_LENGTH = 32;

    private final String label;
    private final String scopeDict;
    private final String scopeLabel;

    CodeTarget(String label, String scopeDict, String scopeLabel) {
        this.label = label;
        this.scopeDict = scopeDict;
        this.scopeLabel = scopeLabel;
    }

    public String label() {
        return label;
    }

    /** 按什么分的字典类型；null = 只有通用规则 */
    public String scopeDict() {
        return scopeDict;
    }

    public String scopeLabel() {
        return scopeLabel;
    }
}
