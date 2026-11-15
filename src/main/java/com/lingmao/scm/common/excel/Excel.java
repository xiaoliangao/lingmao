package com.lingmao.scm.common.excel;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.ss.usermodel.Sheet;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Excel 导入导出（RFC 001 §9、架构 §11）：一份列定义（ExcelSchema）驱动模板、导入、导出。用 Apache POI。
 * <p>
 * 一个资源接上导入导出，Service 和 Controller 各三个方法（接口形状见 RFC 001 §9.5）：
 * <pre>
 * // Service
 * public byte[] importTemplate() {
 *     return Excel.template(excelSchema());
 * }
 * public ImportResult importExcel(MultipartFile file) {
 *     ExcelImport&lt;SupplierExcelRow&gt; rows = Excel.read(excelSchema(), file);    // 逐格解析、校验
 *     ...查文件内部的重复、库里的情况，有问题 rows.error(...)；不拦导入的提示 rows.note(...)
 *     List&lt;SupplierExcelRow&gt; data = rows.dataOrThrow();                       // 有任何问题整批不导，抛 1003
 *     ...在一个事务里写入
 *     return ImportResult.of(data.size(), rows.notes());                            // { total, notes }
 * }
 * public byte[] export(SupplierQuery query) {
 *     List&lt;SupplierExcelRow&gt; rows = ...和列表一样的条件，最多查 Excel.MAX_EXPORT_ROWS + 1 行;
 *     return Excel.export(excelSchema(), rows);                                    // 超过 5 万行抛 1000
 * }
 *
 * // Controller（权限码 &lt;域&gt;:&lt;资源&gt;:import、:export）
 * &#64;GetMapping("/import-template")
 * public ResponseEntity&lt;byte[]&gt; importTemplate() {
 *     return Excel.download("供应商导入模板.xlsx", supplierService.importTemplate());
 * }
 * &#64;PostMapping("/import")
 * public R&lt;ImportResult&gt; importExcel(&#64;RequestParam("file") MultipartFile file) {
 *     return R.ok(supplierService.importExcel(file));
 * }
 * &#64;GetMapping("/export")
 * public ResponseEntity&lt;byte[]&gt; export(&#64;Valid SupplierQuery query) {
 *     return Excel.download(Excel.exportFileName("供应商", LocalDate.now(clock)), supplierService.export(query));
 * }
 * </pre>
 * 文件整个在内存里生成好再返回（5 万行几 MB），不边写边发：中途出错时响应还没开始，前端照样收到普通的 R。
 * <p>
 * 一个文件几张表的（领猫导出的样子：物料信息 + 物料信息SKU），每张表一份列定义、name 是工作表名：
 * 模板 Excel.template(schemas...)，导入 Excel.open(file) 按名字读（见 ExcelBook），导出 Excel.exportBook()（见 ExcelExport）。
 * <p>
 * 前端 ImportDialog 的提示是照这里写的：只收 .xlsx、10MB、5000 行以内；带 * 的列必填；表头别改名、列的顺序可以调；
 * 模板里有"填写说明"；有一处不对整批都不导入；成功时列出提示。改这里的规则要一起改那边。
 */
public final class Excel {

    /** 一个文件最多多少行数据（不算表头和空行） */
    public static final int MAX_IMPORT_ROWS = 5000;

    /** 导入文件最大多少字节，和上传的上限（application.yaml 的 multipart.max-file-size）一样 */
    public static final long MAX_IMPORT_BYTES = 10L * 1024 * 1024;

    /** 一次最多导出多少行。Service 查的时候多查一行（MAX_EXPORT_ROWS + 1），交给 export 判断是不是超了 */
    public static final int MAX_EXPORT_ROWS = 50_000;

    /** .xlsx 的 Content-Type */
    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** 一张表的模板和导入用的工作表名：导入时先找这一页，找不到读第一页 */
    static final String DATA_SHEET = "数据";
    static final String GUIDE_SHEET = "填写说明";
    /** 隐藏的工作表，放选项列的下拉选项 */
    static final String OPTIONS_SHEET = "选项";

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private Excel() {
    }

    /** 导入模板（RFC 001 §9.2）：一张数据表，表名"数据" */
    public static byte[] template(ExcelSchema<?> schema) {
        return ExcelWriter.template(List.of(schema), true);
    }

    /** 几张表的导入模板：每张表名用列定义的 name，按给的顺序排，后面一张"填写说明"写全部的列 */
    public static byte[] template(ExcelSchema<?> first, ExcelSchema<?>... more) {
        List<ExcelSchema<?>> schemas = new ArrayList<>();
        schemas.add(first);
        schemas.addAll(List.of(more));
        if (schemas.size() == 1) {
            return template(first);
        }
        return ExcelWriter.template(schemas, false);
    }

    /**
     * 读导入文件（RFC 001 §9.3）：只收 .xlsx、10MB 以内；表头按名字对；空行跳过；5000 行以内；逐格解析、校验。
     * 文件本身有问题（打不开、没有数据、超行数）抛 1000；表头不对（缺必填列、不认识的列）抛 1003；
     * 单元格的问题记在返回值里，等 Service 把库里的检查也做完，dataOrThrow() 一起报
     */
    public static <R> ExcelImport<R> read(ExcelSchema<R> schema, MultipartFile file) {
        try (ExcelBook book = open(file)) {
            Sheet sheet = ExcelReader.dataSheet(book.workbook());
            if (ExcelReader.isBlank(sheet)) {
                throw new BizException("第 1 行应该是表头，现在是空的。请用下载的模板来填");
            }
            ExcelImport<R> result = ExcelReader.read(schema, sheet, null);
            if (result.headerFailed()) {
                throw ExcelImport.invalid(result.errors());
            }
            if (result.lines().isEmpty()) {
                throw new BizException("文件里没有数据：第 1 行是表头，从第 2 行开始一行填一条");
            }
            return result;
        }
    }

    /**
     * 打开一个几张表的导入文件，按工作表名读（见 ExcelBook）。用 try-with-resources 关掉。
     * 文件本身的检查同 read：只收 .xlsx、10MB 以内，打不开的说清原因（1000）
     */
    public static ExcelBook open(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BizException("只收 .xlsx 文件。.xls、.csv 请先在 Excel 里另存为 .xlsx");
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            throw new BizException("文件超过 " + MAX_IMPORT_BYTES / 1024 / 1024 + "MB，请拆成几个文件分批导入");
        }
        // POI 把整个文件读进内存，流用完就关
        try (InputStream in = file.getInputStream()) {
            return new ExcelBook(ExcelReader.open(in));
        } catch (IOException e) {
            throw new UncheckedIOException("读取上传的文件失败: " + name, e);
        }
    }

    /** 导出文件（RFC 001 §9.4）：一张表，表名是列定义的 name。超过 5 万行抛 1000，提示缩小筛选范围 */
    public static <R> byte[] export(ExcelSchema<R> schema, List<R> rows) {
        return exportBook().sheet(schema, rows).toBytes();
    }

    /** 几张表的导出，见 ExcelExport */
    public static ExcelExport exportBook() {
        return new ExcelExport();
    }

    /** 导出的文件名：供应商-20261105.xlsx。日期由调用方从 Clock 取 */
    public static String exportFileName(String name, LocalDate date) {
        return name + "-" + date.format(FILE_DATE) + ".xlsx";
    }

    /**
     * 把文件作为下载返回。中文文件名放在 filename*=UTF-8''... 里（RFC 5987），前端 request.ts 的 download 认它；
     * 导出、模板都用它
     */
    public static ResponseEntity<byte[]> download(String fileName, byte[] content) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(CONTENT_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(content);
    }
}
