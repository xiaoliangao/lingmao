package com.lingmao.scm.common.pdf;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 后端出 PDF（架构 §11.1）：resources/pdf/ 下的 Thymeleaf 模板 → XHTML → openhtmltopdf 转成 PDF；几份 PDF 按顺序拼成一份。
 * <pre>
 * byte[] sheet = pdfRenderer.render("techpack-sheet", Map.of("sheet", view));   // 模板 resources/pdf/techpack-sheet.html
 * byte[] all = PdfRenderer.merge(List.of(sheet, Files.readAllBytes(appendix)));
 * </pre>
 * 模板按 XML 解析（openhtmltopdf 要合法的 XHTML）：标签要闭合，不能用 &amp;nbsp; 这种 HTML 实体。字体族名写 {@link #FONT_FAMILY}，
 * 有常规（400）和粗体（700）两档。图片用 data: 地址内嵌，不让 openhtmltopdf 自己去取（它取不到要登录、带签名的地址）。
 * <p>
 * 字体（思源黑体的 TrueType 版，lingmao.pdf.font-dir 目录下，dev/fetch-font.sh 下载）第一次用时读进内存，之后每份 PDF 从内存里取，只嵌入用到的字。
 * 没有字体文件时导出报错、说清楚去哪里拿，不退回到不带中文的默认字体（那样导出来全是方框）
 */
@Component
public class PdfRenderer {

    /** 模板里 font-family 写这个名字 */
    public static final String FONT_FAMILY = "SCM Sans";

    private static final String REGULAR = "NotoSansSC-Regular.ttf";
    private static final String BOLD = "NotoSansSC-Bold.ttf";

    private final Path fontDir;
    private final TemplateEngine engine;
    private volatile Map<String, byte[]> fonts;

    public PdfRenderer(@Value("${lingmao.pdf.font-dir}") String fontDir) {
        this.fontDir = Paths.get(fontDir).toAbsolutePath().normalize();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("pdf/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.XML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(true);
        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
    }

    /** 渲染一个模板成 PDF */
    public byte[] render(String template, Map<String, Object> variables) {
        Context context = new Context();
        context.setVariables(variables);
        String xhtml = engine.process(template, context);
        Map<String, byte[]> loaded = fonts();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.useFont(() -> new ByteArrayInputStream(loaded.get(REGULAR)), FONT_FAMILY, 400, FontStyle.NORMAL, true);
            builder.useFont(() -> new ByteArrayInputStream(loaded.get(BOLD)), FONT_FAMILY, 700, FontStyle.NORMAL, true);
            builder.withHtmlContent(xhtml, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("生成 PDF 失败：" + template, e);
        }
        return out.toByteArray();
    }

    /** 几份 PDF 按顺序拼成一份（工艺单 + 附页） */
    public static byte[] merge(List<byte[]> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        try (PDDocument target = new PDDocument()) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (byte[] part : parts) {
                try (PDDocument source = Loader.loadPDF(new RandomAccessReadBuffer(part))) {
                    merger.appendDocument(target, source);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            target.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("拼接 PDF 失败", e);
        }
    }

    /** 这份是不是能打开的 PDF（附页是用户传的文件，坏了就跳过，不让整份导出失败） */
    public static boolean readable(byte[] pdf) {
        try (PDDocument ignored = Loader.loadPDF(new RandomAccessReadBuffer(pdf))) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, byte[]> fonts() {
        Map<String, byte[]> loaded = fonts;
        if (loaded == null) {
            synchronized (this) {
                if (fonts == null) {
                    fonts = Map.of(REGULAR, read(REGULAR), BOLD, read(BOLD));
                }
                loaded = fonts;
            }
        }
        return loaded;
    }

    private byte[] read(String name) {
        Path file = fontDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("导出 PDF 要的字体不在 " + file
                    + "：本机执行 dev/fetch-font.sh；部署包里应该在应用镜像的 /app/fonts/（PDF_FONT_DIR）");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("读不了字体 " + file, e);
        }
    }
}
