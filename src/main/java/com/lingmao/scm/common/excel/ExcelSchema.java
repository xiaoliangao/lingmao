package com.lingmao.scm.common.excel;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 一个资源的 Excel 列定义：资源名 + 怎么新建一行 + 每一列（RFC 001 §9.1）。模板、导入、导出都用它。
 * 行对象是一个普通的类（如 SupplierExcelRow），导入时一格一格 set 进去，导出时一格一格 get 出来。
 * 选项列的选项要从字典取，所以列定义在 Service 里每次现建（便宜，不用缓存）：
 * <pre>
 * private ExcelSchema&lt;SupplierExcelRow&gt; excelSchema() {
 *     return ExcelSchema.of("供应商", SupplierExcelRow::new,
 *             ExcelColumn.text("供应商编码", SupplierExcelRow::getCode, SupplierExcelRow::setCode)
 *                     .required().code().maxLength(32).example("GYS0001"),
 *             ExcelColumn.text("供应商名称", SupplierExcelRow::getName, SupplierExcelRow::setName).required().maxLength(128),
 *             ExcelColumn.choice("分类", options("supplier_type"), SupplierExcelRow::getType, SupplierExcelRow::setType).required(),
 *             ExcelColumn.decimal("税率", 2, SupplierExcelRow::getTaxPct, SupplierExcelRow::setTaxPct).percent().nonNegative(),
 *             ExcelColumn.text("创建时间", SupplierExcelRow::getCreateTime, null));   // setter 是 null：只导出
 * }
 * </pre>
 * 怎么用它做模板、导入、导出见 Excel。
 * <p>
 * 一个文件几张表的（领猫导出的物料是"物料信息""物料信息SKU"两张表），每张表一份列定义，name 就是工作表的名字，
 * 用 ExcelBook 按名字读，见 Excel.open。
 */
public final class ExcelSchema<R> {

    private final String name;
    private final Supplier<R> factory;
    private final List<ExcelColumn<R, ?>> columns;
    /** 认识、但不导入的表头（已经规整过，见 ExcelReader.normalizeHeader） */
    private final Set<String> ignored = new LinkedHashSet<>();

    private ExcelSchema(String name, Supplier<R> factory, List<ExcelColumn<R, ?>> columns) {
        this.name = Objects.requireNonNull(name, "name");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.columns = List.copyOf(columns);
        Set<String> headers = new HashSet<>();
        for (ExcelColumn<R, ?> column : this.columns) {
            if (!headers.add(ExcelReader.normalizeHeader(column.header()))) {
                throw new IllegalArgumentException(name + " 的列定义里表头重复了: " + column.header());
            }
        }
        if (this.columns.isEmpty()) {
            throw new IllegalArgumentException(name + " 一列都没有");
        }
    }

    /**
     * @param name    资源名，导出的工作表名用它；一个文件几张表的，就是这张工作表的名字（模板、导入、导出都按它）
     * @param factory 新建一个空的行对象
     */
    @SafeVarargs
    public static <R> ExcelSchema<R> of(String name, Supplier<R> factory, ExcelColumn<R, ?>... columns) {
        return new ExcelSchema<>(name, factory, List.of(columns));
    }

    /**
     * 认识、但不导入的列：领猫导出的文件里有、我们用不上的列（数据库设计附录写"丢弃"的）。
     * 文件里有这些列不报"不认识"，也不读；模板里没有它们。别的不认识的列照样报错（拼错的列不会被悄悄丢掉）
     */
    public ExcelSchema<R> ignore(String... headers) {
        for (String header : headers) {
            String normalized = ExcelReader.normalizeHeader(header);
            boolean isColumn = columns.stream().anyMatch(c -> ExcelReader.normalizeHeader(c.header()).equals(normalized));
            if (isColumn || !ignored.add(normalized)) {
                throw new IllegalArgumentException(name + " 的忽略列重复了: " + header);
            }
        }
        return this;
    }

    public String name() {
        return name;
    }

    /** 返回当前可见列的位置，供分页聚合在构建过长单元格前报告准确位置。 */
    public int columnIndex(String header) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).header().equals(header)) return i;
        throw new IllegalArgumentException("工作表没有这列: " + header);
    }

    /** 在写出任何字节之前整列去掉（如没有看价权限的列）。 */
    public ExcelSchema<R> without(String... headers) {
        Set<String> excluded = Set.of(headers);
        return new ExcelSchema<>(name, factory, columns.stream().filter(c -> !excluded.contains(c.header())).toList());
    }

    boolean isIgnored(String normalizedHeader) {
        return ignored.contains(normalizedHeader);
    }

    R newRow() {
        return factory.get();
    }

    /** 全部列，导出用 */
    List<ExcelColumn<R, ?>> columns() {
        return columns;
    }

    /** 能导入的列（有 setter 的），模板只有这些 */
    List<ExcelColumn<R, ?>> importColumns() {
        return columns.stream().filter(ExcelColumn::importable).toList();
    }
}
