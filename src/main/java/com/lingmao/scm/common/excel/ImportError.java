package com.lingmao.scm.common.excel;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 导入时的一处问题或一条提示：1003 的 data（错误清单）、导入成功时的 notes（提示清单）里每一项都是它，
 * 前端 BizErrorTable 显示成"工作表 / 行 / 列 / 问题"几列。
 * <ul>
 *   <li>sheet：哪张工作表。只读一张表的导入是 null，不出现在 JSON 里（前端就不显示这一列）</li>
 *   <li>row：Excel 里看到的行号，表头是第 1 行；说的是整张表、整个文件的（缺了一张表、提示太多没列全）是 0</li>
 *   <li>column：表头的名字（不带 *）；说的是整行的是 null</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportError(String sheet, int row, String column, String message) {

    /** 只读一张表的导入用：不带工作表名 */
    public ImportError(int row, String column, String message) {
        this(null, row, column, message);
    }
}
