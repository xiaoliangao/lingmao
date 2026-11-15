package com.lingmao.scm.common.excel;

/**
 * 选项列（ExcelColumn.choice）的一个选项：显示的文字、存的值。一般从字典来：
 * <pre>
 * dictDataService.listByType("supplier_type").stream()
 *         .map(o -> new ExcelOption(o.label(), o.value(), o.enabled())).toList()
 * </pre>
 * 停用的也传进来：模板下拉里不列、导入时不认，只用来导出时把旧数据里的值翻成文字
 */
public record ExcelOption(String label, String value, boolean enabled) {

    /** 启用的选项 */
    public ExcelOption(String label, String value) {
        this(label, value, true);
    }
}
