package com.lingmao.scm.common.excel;

/**
 * 导入文件里浮在某一格上的一张图片（ExcelBook.images 取出来的）。
 *
 * @param row       图片左上角落在第几行，和 ExcelImport.Line.row 一样是 Excel 里看到的行号（表头第 1 行）
 * @param extension jpg、png、gif；别的格式（emf、wmf、tiff……）是 null，data 是空的，调用方在提示里说一句没导
 * @param data      图片文件本身
 */
public record ExcelImage(int row, String extension, byte[] data) {

    public boolean supported() {
        return extension != null;
    }
}
