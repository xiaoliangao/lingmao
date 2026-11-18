package com.lingmao.scm.common.pdf;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class A2PdfResourceTest {
    @TempDir Path temporary;

    @Test void actualRendererNeverFetchesHttpOrLocalCssButKeepsEmbeddedRasterAndChinese() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            Path css = temporary.resolve("local.css"); Files.writeString(css, "body:before { content: 'LOCAL_FILE_SENTINEL'; }");
            var png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String html = """
                    <html><head><link rel="stylesheet" href="%s"/><link rel="stylesheet" href="%s/local.css"/>
                    <style>body { font-family: 'SCM Sans'; } @import url('%s/import.css');</style></head>
                    <body>中文安全测试 Allowed content<img src="%s/image.png"/><img src="data:image/png;base64,%s"/></body></html>
                    """.formatted(css.toUri(), base, base, base, Base64.getEncoder().encodeToString(png.toByteArray()));
            PdfRenderer renderer = new PdfRenderer(System.getenv().getOrDefault("PDF_FONT_DIR", "dev/fonts"));
            byte[] bytes = renderer.renderXhtml(html);
            assertThat(requests).hasValue(0);
            try (var document = Loader.loadPDF(bytes)) {
                assertThat(new PDFTextStripper().getText(document)).contains("中文安全测试", "Allowed content").doesNotContain("LOCAL_FILE_SENTINEL");
                int embeddedImages = 0;
                for (var page : document.getPages()) for (var name : page.getResources().getXObjectNames())
                    if (page.getResources().isImageXObject(name)) embeddedImages++;
                assertThat(embeddedImages).isPositive();
            }
        } finally { server.stop(0); }
    }
}
