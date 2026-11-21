package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个文件几张表的导入：按工作表的名字读（领猫导出的物料是"物料信息""物料信息SKU"两张表，"物料信息多单位"可有可无）。
 * 每张表一份列定义，列定义的 name 就是工作表名。由 Excel.open 打开：
 * <pre>
 * try (ExcelBook book = Excel.open(multipartFile)) {
 *     ExcelImport&lt;MaterialRow&gt; materials = book.sheet(materialSchema());        // 必须有这张表
 *     ExcelImport&lt;SkuRow&gt; skus = book.sheet(skuSchema());
 *     ExcelImport&lt;UnitRow&gt; units = book.optionalSheet(unitSchema());          // 没有这张表：空的，不报错
 *     ...每张表自己的检查、跨表的检查：skus.error(line.row(), "物料编码", "「物料信息」里没有这个物料")
 *     ...不拦导入的提示：materials.note(line.row(), "颜色", "颜色 C012 没有，按「C012 藏青」新建了")
 *     book.throwIfInvalid();                                    // 所有表的问题一起报，有就抛 1003
 *     ...在一个事务里写入
 *     return book.result(materials.lines().size());             // { total, notes }
 * }
 * </pre>
 * 问题清单、提示清单里每一项都带工作表名，按读表的顺序、表里按行号排。
 */
public final class ExcelBook implements AutoCloseable {

    /** images 认的图片格式：MIME 类型 → 存文件用的扩展名（上传接口也收这几种） */
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/gif", "gif");

    private final Workbook workbook;
    /** 读过的表，按读的顺序：问题、提示按这个顺序列 */
    private final List<ExcelImport<?>> sheets = new ArrayList<>();

    ExcelBook(Workbook workbook) {
        this.workbook = workbook;
    }

    Workbook workbook() {
        return workbook;
    }

    /** 读一张必须有的表。没有这张表、或者整张是空的，记一处问题（throwIfInvalid 时一起报），返回空的 */
    public <R> ExcelImport<R> sheet(ExcelSchema<R> schema) {
        return read(schema, true);
    }

    /** 读一张可以没有的表。没有、或者整张是空的，返回空的，不算问题 */
    public <R> ExcelImport<R> optionalSheet(ExcelSchema<R> schema) {
        return read(schema, false);
    }

    private <R> ExcelImport<R> read(ExcelSchema<R> schema, boolean required) {
        String name = schema.name();
        Sheet sheet = workbook.getSheet(name);
        ExcelImport<R> result;
        if (sheet == null || ExcelReader.isBlank(sheet)) {
            List<ImportError> errors = !required ? List.of() : List.of(new ImportError(name, 0, null, sheet == null
                    ? "文件里没有「" + name + "」这张工作表：表名要和模板里的一样"
                    : "「" + name + "」这张表是空的：第 1 行应该是表头"));
            result = new ExcelImport<>(name, List.of(), errors, required);
        } else {
            result = ExcelReader.read(schema, sheet, name);
        }
        sheets.add(result);
        return result;
    }

    /**
     * 一张表里浮在某一列上的图片（领猫导出的"图片"列：一行一张，图片左上角落在那一格里）。
     * 按行号排，同一行几张的按从左到右、从上到下；左上角不在这一列的、落在表头上的不算。
     * 没有这张表、表头里没有这一列、没有图片，都返回空的。不检查行上有没有数据，调用方按行号对自己的行
     */
    public List<ExcelImage> images(String sheetName, String header) {
        Sheet sheet = workbook.getSheet(sheetName);
        Integer column = sheet == null ? null : columnOf(sheet, header);
        Drawing<?> drawing = column == null ? null : sheet.getDrawingPatriarch();
        if (drawing == null) {
            return List.of();
        }
        // 位置、偏移、字节都在这里一次取出来：POI 读坏文件的锚点、偏移（超出 int）、图片数据都可能抛，坏的那张跳过，不让整批报错
        record Placed(int row, int dx, int dy, ExcelImage image) {
        }
        List<Placed> placed = new ArrayList<>();
        for (Shape shape : drawing) {
            if (!(shape instanceof Picture picture)) {
                continue;
            }
            try {
                ClientAnchor anchor = picture.getClientAnchor();
                if (anchor == null || anchor.getCol1() != column || anchor.getRow1() < 1) {
                    continue;
                }
                PictureData data = picture.getPictureData();
                String extension = data == null ? null : IMAGE_EXTENSIONS.get(data.getMimeType());
                byte[] bytes = extension == null ? new byte[0] : data.getData();
                placed.add(new Placed(anchor.getRow1(), anchor.getDx1(), anchor.getDy1(),
                        new ExcelImage(anchor.getRow1() + 1, extension, bytes)));
            } catch (RuntimeException broken) {
                // 这一张读不出来：当没有
            }
        }
        placed.sort(Comparator.comparingInt(Placed::row).thenComparingInt(Placed::dx).thenComparingInt(Placed::dy));
        return placed.stream().map(Placed::image).toList();
    }

    /** 表头（第 1 行）里叫 header 的那一列，从 0 数；没有返回 null。表头文字和读表时一样处理（不换行空格、星号都去掉） */
    private static Integer columnOf(Sheet sheet, String header) {
        Row first = sheet.getRow(0);
        if (first == null) {
            return null;
        }
        String wanted = ExcelReader.normalizeHeader(header);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        for (Cell cell : first) {
            if (wanted.equals(ExcelReader.normalizeHeader(ExcelReader.textOf(cell, formatter)))) {
                return cell.getColumnIndex();
            }
        }
        return null;
    }

    public boolean hasErrors() {
        return sheets.stream().anyMatch(ExcelImport::hasErrors);
    }

    /** 所有表的问题：按读表的顺序，表里按行号 */
    public List<ImportError> errors() {
        return sheets.stream().flatMap(sheet -> sheet.errors().stream()).toList();
    }

    /** 所有表的提示，顺序同 errors */
    public List<ImportError> notes() {
        return sheets.stream().flatMap(sheet -> sheet.notes().stream()).toList();
    }

    /** 有任何一处问题：抛 1003，data 是所有表的问题。读过的表一行数据都没有：抛 1000 */
    public void throwIfInvalid() {
        List<ImportError> errors = errors();
        if (!errors.isEmpty()) {
            throw ExcelImport.invalid(errors);
        }
        if (sheets.stream().allMatch(sheet -> sheet.lines().isEmpty())) {
            throw new BizException("文件里没有数据：每张表第 1 行是表头，从第 2 行开始一行填一条");
        }
    }

    /** 导入成功的返回：条数由调用方说（几张表的行数怎么算一条，各资源不一样），提示是所有表的 */
    public ImportResult result(int total) {
        return ImportResult.of(total, notes());
    }

    @Override
    public void close() {
        try {
            workbook.close();
        } catch (IOException e) {
            throw new UncheckedIOException("关闭导入文件失败", e);
        }
    }
}
