package com.lingmao.scm.module.style.dto;

/** 只读导出投影；不把整个款式的色码矩阵装入内存。 */
public final class StyleMatrixExportRows {
    private StyleMatrixExportRows() { }
    public record SizeAxis(long styleId, String sizeCode) { }
    public record ColorAxis(long relationId, long styleId, String styleCode, String styleName,
                            long colorId, String colorName) { }
    public record SkuCell(long skuId, long relationId, long styleId, long colorId, String sizeCode) { }
}
