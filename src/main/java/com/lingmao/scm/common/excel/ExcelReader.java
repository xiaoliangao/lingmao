package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.EmptyFileException;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 读导入文件（RFC 001 §9.3）。对外的入口是 Excel.read（一张表）、Excel.open（按名字读几张表） */
final class ExcelReader {

    /**
     * .xlsx 是个 zip 包，里面每个文件解压后最大多少。5000 行几十列的表格，工作表的 XML 也就十来 MB；
     * 不限的话一个 10MB 的"压缩炸弹"能解出几 GB，整个应用内存撑爆（POI 默认上限是 4GB）。
     * 这是 POI 的全局设置，整个应用只有这里读 Excel
     */
    static {
        ZipSecureFile.setMaxEntrySize(ExcelArchiveGuard.MAX_ENTRY_BYTES);
        ZipSecureFile.setMaxFileCount(ExcelArchiveGuard.MAX_FILES);
    }

    private ExcelReader() {
    }

    /** 用 POI 打开之前，先检查真实的 OOXML 压缩包和它要占的内存上限。 */
    static Workbook open(InputStream in) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(ExcelArchiveGuard.inspect(in)));
        } catch (BizException e) {
            throw e;
        } catch (EmptyFileException e) {
            throw new BizException("文件是空的");
        } catch (EncryptedDocumentException e) {
            throw new BizException("文件设了密码，请在 Excel 里去掉密码再导入");
        } catch (IOException | RuntimeException e) {
            throw new BizException("文件打不开，可能不是 Excel 文件或者已经损坏。请用 Excel 打开、另存为 .xlsx 再导入");
        }
    }

    /** 只读一张表的导入读哪一张："数据"，没有就第一张（工作表改了名、或者数据贴进了新建的文件） */
    static Sheet dataSheet(Workbook workbook) {
        Sheet sheet = workbook.getSheet(Excel.DATA_SHEET);
        if (sheet == null && workbook.getNumberOfSheets() > 0) {
            sheet = workbook.getSheetAt(0);
        }
        if (sheet == null) {
            throw new BizException("文件里没有工作表");
        }
        return sheet;
    }

    /** 整张表是空的：一行都没有，或者第 1 行（表头）一个字都没有 */
    static boolean isBlank(Sheet sheet) {
        Row header = sheet.getRow(0);
        if (header == null) {
            return true;
        }
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (Cell cell : header) {
            if (!textOf(cell, formatter).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 读一张表：先对表头，再逐行解析。表头没对上（缺必填列、不认识的列），行都不读，返回的结果 headerFailed：
     * 多半是改了名，数据会对错列或者悄悄丢掉，后面的行读了也没意义。
     * 超过 5000 行直接抛 1000，整个文件不收。
     *
     * @param sheetName 问题清单里写的工作表名；只读一张表的导入传 null，清单里不带表名
     */
    static <R> ExcelImport<R> read(ExcelSchema<R> schema, Sheet sheet, String sheetName) {
        // 数字按显示的文字读（不经过 double）。Locale 固定：不然小数点跟着服务器的语言设置走，德语环境下是逗号
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        List<ImportError> headerErrors = new ArrayList<>();
        Map<Integer, ExcelColumn<R, ?>> columns = readHeader(schema, sheet.getRow(0), formatter, sheetName, headerErrors);
        if (!headerErrors.isEmpty()) {
            return new ExcelImport<>(sheetName, List.of(), headerErrors, true);
        }

        List<ExcelImport.Line<R>> lines = new ArrayList<>();
        List<ImportError> errors = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            Map<Integer, String> texts = new HashMap<>();
            columns.keySet().forEach(col -> texts.put(col, textOf(row.getCell(col), formatter)));
            // 空行跳过：有的人在中间空几行分组，删了数据的行也常常还留着格式
            if (texts.values().stream().allMatch(String::isEmpty)) {
                continue;
            }
            if (lines.size() >= Excel.MAX_IMPORT_ROWS) {
                String where = sheetName == null ? "" : "「" + sheetName + "」这张表";
                throw new BizException(where + "超过 " + Excel.MAX_IMPORT_ROWS + " 行了，请拆成几个文件分批导入");
            }
            int rowNumber = index + 1;
            R data = schema.newRow();
            columns.forEach((col, column) -> {
                Cell cell = row.getCell(col);
                String value = texts.get(col);
                boolean textColumn = column.kind() == ExcelColumn.Kind.TEXT || column.kind() == ExcelColumn.Kind.CHOICE;
                boolean formula = cell != null && cell.getCellType() == CellType.FORMULA;
                String problem;
                if (formula || textColumn && dangerousPrefix(value)) {
                    problem = "不允许公式或公式前缀，请粘贴为值；纯文字请在前面加英文单引号";
                } else {
                    // 我们导出时给公式前缀加了转义，导回来时还原成原文。
                    if (textColumn && value.startsWith("'") && dangerousPrefix(value.substring(1))) value = value.substring(1).strip();
                    problem = column.readInto(data, cell, value);
                }
                if (problem != null) {
                    errors.add(new ImportError(sheetName, rowNumber, column.header(), problem));
                }
            });
            lines.add(new ExcelImport.Line<>(rowNumber, data));
        }
        return new ExcelImport<>(sheetName, lines, errors, false);
    }

    /**
     * 表头按名字对，不按位置：甲方调了列的顺序也能导（RFC 001 §9.3）。* 和空白不算，导出的文件（表头不带 *）改了也能导回来。
     * 问题记进 errors：缺了必填列、不认识的列、同一列出现两次。ignore 过的列认识、不读。
     * 返回 列号 → 列定义，只含能导入的列
     */
    private static <R> Map<Integer, ExcelColumn<R, ?>> readHeader(ExcelSchema<R> schema, Row header, DataFormatter formatter,
                                                                   String sheetName, List<ImportError> errors) {
        Map<Integer, ExcelColumn<R, ?>> columns = new LinkedHashMap<>();
        if (header == null) {
            errors.add(new ImportError(sheetName, 1, null, "第 1 行应该是表头，现在是空的。请用下载的模板来填"));
            return columns;
        }
        Map<String, ExcelColumn<R, ?>> byName = new HashMap<>();
        schema.columns().forEach(column -> byName.put(normalizeHeader(column.header()), column));

        Set<ExcelColumn<R, ?>> seen = new HashSet<>();
        for (Cell cell : header) {
            String text = textOf(cell, formatter);
            if (text.isEmpty() || schema.isIgnored(normalizeHeader(text))) {
                continue;
            }
            ExcelColumn<R, ?> column = byName.get(normalizeHeader(text));
            if (column == null) {
                errors.add(new ImportError(sheetName, 1, text, "不认识这一列：表头要和模板里的一样，用不上的列请整列删掉"));
            } else if (!seen.add(column)) {
                errors.add(new ImportError(sheetName, 1, column.header(), "这一列出现了两次"));
            } else if (column.importable()) {
                columns.put(cell.getColumnIndex(), column);
            }
            // 只导出的列（创建时间这些）：导出的文件改了再导回来时会带着，不读
        }
        for (ExcelColumn<R, ?> column : schema.importColumns()) {
            if (column.isRequired() && !seen.contains(column)) {
                errors.add(new ImportError(sheetName, 1, column.header(), "缺少这一列，它是必填的"));
            }
        }
        return columns;
    }

    /** 表头比较时忽略 *（必填标记）和所有空白（有人在表头里换行） */
    static String normalizeHeader(String header) {
        return header.replace("*", "").replace("＊", "").replaceAll("\\s+", "");
    }

    /** 单元格显示的文字，去掉首尾空白。网页上复制来的不换行空格（ ）也算空白 */
    private static String textOf(Cell cell, DataFormatter formatter) {
        return formatter.formatCellValue(cell).replace(' ', ' ').strip();
    }

    private static boolean dangerousPrefix(String value) {
        String start = value.replace(' ', ' ').stripLeading();
        return !start.isEmpty() && "=+-@".indexOf(start.charAt(0)) >= 0;
    }
}
