package com.lingmao.scm.common.file;

import com.lingmao.scm.common.exception.BizException;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2UploadContentTest {
    @TempDir Path directory;
    @Test void actualOfficeContainersMustMatchTheDeclaredType() throws Exception {
        Path xlsx = directory.resolve("valid.xlsx"), xls = directory.resolve("valid.xls");
        try (var book = new XSSFWorkbook(); var output = Files.newOutputStream(xlsx)) {
            book.createSheet("合成数据").createRow(0).createCell(0).setCellValue("普通文本"); book.write(output);
        }
        try (var book = new HSSFWorkbook(); var output = Files.newOutputStream(xls)) {
            book.createSheet("合成数据").createRow(0).createCell(0).setCellValue("普通文本"); book.write(output);
        }
        assertThatCode(() -> UploadContent.verify(xlsx, "xlsx")).doesNotThrowAnyException();
        assertThatCode(() -> UploadContent.verify(xls, "xls")).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadContent.verify(xlsx, "docx")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> UploadContent.verify(xls, "doc")).isInstanceOf(BizException.class);
    }
    @Test void archiveTraversalAndExcessExpansionAreRejectedWithoutExtraction() throws Exception {
        for (String name : new String[]{"../outside.xml", "xl/vbaProject.bin", "xl/externalLinks/link.xml"}) {
            Path file = directory.resolve("case-" + System.nanoTime() + ".xlsx");
            try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1}); zip.closeEntry();
            }
            assertThatThrownBy(() -> UploadContent.verify(file, "xlsx")).isInstanceOf(BizException.class);
        }
        Path expanded = directory.resolve("expanded.xlsx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(expanded))) {
            zip.putNextEntry(new ZipEntry("large.bin"));
            byte[] block = new byte[8192]; for (int n = 0; n < 3000; n++) zip.write(block); zip.closeEntry();
        }
        assertThatThrownBy(() -> UploadContent.verify(expanded, "xlsx")).isInstanceOf(BizException.class).hasMessageContaining("压缩文件展开");
    }
    @Test void corruptAndMislabeledFormatsReturnControlledValidationErrors() throws Exception {
        Path file = directory.resolve("corrupt.xlsx"); Files.write(file, new byte[]{'P', 'K', 3, 4, 0, 0});
        assertThatThrownBy(() -> UploadContent.verify(file, "xlsx")).isInstanceOf(BizException.class);
        Files.writeString(file, "<html>synthetic</html>");
        for (String extension : new String[]{"jpg", "png", "pdf", "psd", "ai", "xls", "doc", "xlsx", "docx"})
            assertThatThrownBy(() -> UploadContent.verify(file, extension)).isInstanceOf(BizException.class);
    }
}
