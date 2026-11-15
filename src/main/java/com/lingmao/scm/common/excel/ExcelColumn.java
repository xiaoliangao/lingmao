package com.lingmao.scm.common.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Excel 里的一列：表头、类型、是否必填、怎么校验、导入时写进行对象的哪个字段、导出时取哪个值。
 * 模板、导入、导出都按它来，表头和规则只写这一处（RFC 001 §9.1）。用法见 ExcelSchema。
 * <p>
 * 类型决定解析和格式：
 * <ul>
 *   <li>text：文字，首尾空白去掉。编码类的加 .code()，模板里整列设成文本格式，0012 不会变成 12</li>
 *   <li>integer：整数（件数）</li>
 *   <li>decimal：数字，限定最多几位小数（数量 3、单价 4、金额 2，架构 §6），超了报错，不偷偷四舍五入</li>
 *   <li>date：日期，认 Excel 的日期单元格和 2026-11-05、2026/11/5 这样的文字</li>
 *   <li>choice：从选项里选（一般是字典），表格里填显示的文字，进库的是值；模板里带下拉</li>
 * </ul>
 * 数字一律按单元格<b>显示的文字</b>转成 BigDecimal，不经过 double：直接取 double 的话 0.1 会变成 0.1000000000000000055。
 * 千分位的逗号去掉；设成百分比格式的单元格只有 .percent() 的列认（显示 3% 读成 3，我们的百分比列存的就是 3）。
 * 例外是"常规"格式下显示成科学计数法的长数字（条码），见 unscientific。
 * <p>
 * setter 传 null 的列只导出不导入（创建时间、状态文字这类），不进模板；导出的文件改了再导回来时，这些列会被忽略。
 */
public final class ExcelColumn<R, V> {

    /** 列的类型：决定怎么解析、模板里设什么格式、导出时写成什么单元格 */
    enum Kind { TEXT, INTEGER, DECIMAL, DATE, CHOICE }

    private static final Pattern DATE_TEXT = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})");
    /** "常规"格式显示成科学计数法的数字：1.23457E+12 */
    private static final Pattern SCIENTIFIC = Pattern.compile("-?\\d+(\\.\\d+)?E[-+]\\d+");

    /** 报"不在选项里"、写填写说明时最多列出几个选项，再多就说"等 N 项" */
    private static final int LISTED_OPTIONS = 20;

    private final String header;
    private final Kind kind;
    private final Function<R, V> getter;
    private final BiConsumer<R, V> setter;
    /** decimal：最多几位小数 */
    private final int scale;
    /** choice：全部选项，停用的也在 */
    private final List<ExcelOption> options;
    /** 额外的校验：返回给用户看的问题，没问题返回 null */
    private final List<Function<V, String>> rules = new ArrayList<>();

    private boolean required;
    private boolean code;
    private boolean percent;
    private int maxLength;
    private String example;
    private String note;

    private ExcelColumn(String header, Kind kind, Function<R, V> getter, BiConsumer<R, V> setter,
                        int scale, List<ExcelOption> options) {
        if (header == null || header.isBlank() || header.contains("*")) {
            throw new IllegalArgumentException("表头不能为空，也不要自己写 *（必填用 .required()）: " + header);
        }
        this.header = header.strip();
        this.kind = kind;
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = setter;
        this.scale = scale;
        this.options = options;
    }

    /** 文字列 */
    public static <R> ExcelColumn<R, String> text(String header, Function<R, String> getter, BiConsumer<R, String> setter) {
        return new ExcelColumn<>(header, Kind.TEXT, getter, setter, 0, List.of());
    }

    /** 整数列（件数） */
    public static <R> ExcelColumn<R, Integer> integer(String header, Function<R, Integer> getter, BiConsumer<R, Integer> setter) {
        return new ExcelColumn<>(header, Kind.INTEGER, getter, setter, 0, List.of());
    }

    /** 数字列，最多 scale 位小数：数量 3、单价 4、金额 2、单件用量 6（架构 §6） */
    public static <R> ExcelColumn<R, BigDecimal> decimal(String header, int scale,
                                                          Function<R, BigDecimal> getter, BiConsumer<R, BigDecimal> setter) {
        if (scale < 0) {
            throw new IllegalArgumentException("小数位数不能是负数: " + header);
        }
        return new ExcelColumn<>(header, Kind.DECIMAL, getter, setter, scale, List.of());
    }

    /** 日期列（业务日期：交期、下单日……） */
    public static <R> ExcelColumn<R, LocalDate> date(String header, Function<R, LocalDate> getter, BiConsumer<R, LocalDate> setter) {
        return new ExcelColumn<>(header, Kind.DATE, getter, setter, 0, List.of());
    }

    /** 选项列：表格里填显示的文字（填值也认），进库的是值。options 停用的也传，见 ExcelOption */
    public static <R> ExcelColumn<R, String> choice(String header, List<ExcelOption> options,
                                                    Function<R, String> getter, BiConsumer<R, String> setter) {
        return new ExcelColumn<>(header, Kind.CHOICE, getter, setter, 0, List.copyOf(options));
    }

    /** 必填：表头带 *，文件里缺这一列整个文件不收，某一行空着报"必填" */
    public ExcelColumn<R, V> required() {
        this.required = true;
        return this;
    }

    /** 编码类的文字列：模板里整列设成文本格式，不然 Excel 会把 0012 变成 12 */
    public ExcelColumn<R, V> code() {
        requireKind("code()", Kind.TEXT);
        this.code = true;
        return this;
    }

    /** 文字最长几个字（和表里 VARCHAR 的长度一致），按字数算，一个汉字算一个 */
    public ExcelColumn<R, V> maxLength(int maxLength) {
        requireKind("maxLength()", Kind.TEXT);
        this.maxLength = maxLength;
        return this;
    }

    /** 百分比列（存 3 表示 3%，架构 §6）：单元格设成百分比格式显示 3%，读成 3；直接填 3 也是 3 */
    public ExcelColumn<R, V> percent() {
        requireKind("percent()", Kind.DECIMAL, Kind.INTEGER);
        this.percent = true;
        return this;
    }

    /** 数字不能是负数 */
    public ExcelColumn<R, V> nonNegative() {
        requireKind("nonNegative()", Kind.DECIMAL, Kind.INTEGER);
        return check(value -> new BigDecimal(value.toString()).signum() < 0 ? "不能是负数" : null);
    }

    /** 额外的校验，在解析成功以后跑：返回给用户看的问题（如"不能超过 100"），没问题返回 null */
    public ExcelColumn<R, V> check(Function<V, String> rule) {
        rules.add(Objects.requireNonNull(rule));
        return this;
    }

    /** 填写说明里的例子 */
    public ExcelColumn<R, V> example(String example) {
        this.example = example;
        return this;
    }

    /** 填写说明里补充的话，如"领猫导出的供应商编号原样填" */
    public ExcelColumn<R, V> note(String note) {
        this.note = note;
        return this;
    }

    // ---------- 下面给 Excel 内部用 ----------

    String header() {
        return header;
    }

    Kind kind() {
        return kind;
    }

    boolean isRequired() {
        return required;
    }

    boolean isCode() {
        return code;
    }

    /** 能导入（有 setter）。只导出的列不进模板 */
    boolean importable() {
        return setter != null;
    }

    /** 模板里的表头：必填的前面带 * */
    String templateHeader() {
        return required ? "*" + header : header;
    }

    String example() {
        return example;
    }

    /** 模板下拉里列的选项：只有启用的 */
    List<String> enabledLabels() {
        return options.stream().filter(ExcelOption::enabled).map(ExcelOption::label).toList();
    }

    /** 填写说明里"怎么填"那一格 */
    String describe() {
        String how = switch (kind) {
            case TEXT -> (code ? "文字（按文本填，开头的 0 会保留）" : "文字") + (maxLength > 0 ? "，最多 " + maxLength + " 个字" : "");
            case INTEGER -> "整数";
            case DECIMAL -> scale == 0 ? "整数" : "数字，最多 " + scale + " 位小数";
            case DATE -> "日期，如 2026-11-05";
            case CHOICE -> "从下拉里选：" + listLabels(enabledLabels());
        };
        if (percent) {
            how += "。填 3 表示 3%";
        }
        return note == null ? how : how + "。" + note;
    }

    /** 导出时这一格的值：文字、数字、日期原样；选项翻成显示的文字（停用的也翻），翻不出来的给值本身 */
    Object exportValue(R row) {
        V value = getter.apply(row);
        if (value == null || kind != Kind.CHOICE) {
            return value;
        }
        return options.stream().filter(option -> option.value().equals(value)).map(ExcelOption::label)
                .findFirst().orElse(value.toString());
    }

    /**
     * 解析一个单元格，写进 row。有问题返回给用户看的说明（不写），没问题返回 null。
     * text 是单元格显示的文字，已经去掉首尾空白；空的当没填
     */
    String readInto(R row, Cell cell, String text) {
        if (text.isEmpty()) {
            return required ? "必填" : null;
        }
        V value;
        try {
            value = parse(cell, text);
        } catch (InvalidCell e) {
            return e.getMessage();
        }
        for (Function<V, String> rule : rules) {
            String problem = rule.apply(value);
            if (problem != null) {
                return problem;
            }
        }
        setter.accept(row, value);
        return null;
    }

    @SuppressWarnings("unchecked")
    private V parse(Cell cell, String text) {
        if (cell != null && effectiveType(cell) == CellType.ERROR) {
            throw new InvalidCell("单元格里是公式算出来的错误值 " + text + "，请改成具体的值");
        }
        Object value = switch (kind) {
            case TEXT -> parseText(unscientific(cell, text));
            case INTEGER -> parseInteger(unscientific(cell, text));
            case DECIMAL -> parseDecimal(unscientific(cell, text));
            case DATE -> parseDate(cell, text);
            case CHOICE -> parseChoice(text);
        };
        return (V) value;
    }

    /**
     * Excel 的"常规"格式，超过 11 位的数字显示成科学计数法：13 位的条码显示 1.23457E+12。按显示的文字读就丢了后面几位，
     * 这时改用单元格里存的数：15 位有效数字以内 double 存得准，BigDecimal.valueOf 取最短的写法，和填进去的一样
     */
    private static String unscientific(Cell cell, String text) {
        if (cell != null && effectiveType(cell) == CellType.NUMERIC && SCIENTIFIC.matcher(text).matches()) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return text;
    }

    private String parseText(String text) {
        int length = text.codePointCount(0, text.length());
        if (maxLength > 0 && length > maxLength) {
            throw new InvalidCell("最多 " + maxLength + " 个字，现在 " + length + " 个");
        }
        return text;
    }

    private Integer parseInteger(String text) {
        BigDecimal number = number(text).stripTrailingZeros();
        if (number.scale() > 0) {
            throw new InvalidCell("要填整数");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidCell("数字太大了");
        }
    }

    private BigDecimal parseDecimal(String text) {
        BigDecimal number = number(text).stripTrailingZeros();
        if (number.scale() > scale) {
            throw new InvalidCell(scale == 0 ? "要填整数" : "最多 " + scale + " 位小数");
        }
        // 100 去掉末尾的 0 会变成 1E+2，改回 100
        return number.scale() < 0 ? number.setScale(0) : number;
    }

    /** 显示的文字 → BigDecimal：去掉千分位逗号；百分比列去掉 % */
    private BigDecimal number(String text) {
        String plain = text.replace(",", "").replace(" ", "");
        if (percent && plain.endsWith("%")) {
            plain = plain.substring(0, plain.length() - 1);
        }
        try {
            return new BigDecimal(plain);
        } catch (NumberFormatException e) {
            throw new InvalidCell("要填数字");
        }
    }

    private LocalDate parseDate(Cell cell, String text) {
        if (cell != null && effectiveType(cell) == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        Matcher matcher = DATE_TEXT.matcher(text);
        if (!matcher.matches()) {
            throw new InvalidCell("日期格式不对，填 2026-11-05 这样的");
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (DateTimeException e) {
            throw new InvalidCell("没有这一天: " + text);
        }
    }

    /** 填显示的文字或值都认；只认启用的选项，填了停用的单独说一句 */
    private String parseChoice(String text) {
        List<ExcelOption> matched = options.stream()
                .filter(option -> option.label().equals(text) || option.value().equals(text))
                .toList();
        return matched.stream().filter(ExcelOption::enabled).map(ExcelOption::value).findFirst()
                .orElseThrow(() -> matched.isEmpty()
                        ? new InvalidCell("不在选项里，可选：" + listLabels(enabledLabels()))
                        : new InvalidCell("「" + matched.getFirst().label() + "」已停用，不能再用"));
    }

    private void requireKind(String method, Kind... allowed) {
        if (!List.of(allowed).contains(kind)) {
            throw new IllegalStateException(header + " 是 " + kind + " 列，不能用 " + method);
        }
    }

    private static String listLabels(List<String> labels) {
        if (labels.isEmpty()) {
            return "（还没有可选的，找管理员在字典管理里添加）";
        }
        String listed = labels.stream().limit(LISTED_OPTIONS).collect(Collectors.joining("、"));
        return labels.size() > LISTED_OPTIONS ? listed + " 等 " + labels.size() + " 项" : listed;
    }

    /** 公式单元格看它算出来的结果是什么类型（文件里存着的结果，不重新算） */
    private static CellType effectiveType(Cell cell) {
        return cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
    }

    /** 单元格不合格：消息就是给用户看的那句话 */
    private static final class InvalidCell extends RuntimeException {
        InvalidCell(String message) {
            super(message, null, false, false);
        }
    }
}
