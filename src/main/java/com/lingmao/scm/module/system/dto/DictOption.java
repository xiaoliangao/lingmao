package com.lingmao.scm.module.system.dto;

/**
 * 字典的一个选项：显示的文字、存的值、状态（1 启用 0 停用）。
 * 停用的也给：旧单据里存着停用的值，列表里要能显示它的文字；下拉框只列启用的（前端 DictSelect 过滤）。
 * 后端拿它校验"填的值合不合法"时，新数据只认启用的
 */
public record DictOption(String label, String value, Integer status) {

    public boolean enabled() {
        return Integer.valueOf(1).equals(status);
    }
}
