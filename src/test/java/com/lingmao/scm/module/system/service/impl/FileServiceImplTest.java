package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.UploadCatalog;
import java.nio.charset.StandardCharsets;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileServiceImplTest {

    /** 东八区 11 月 5 日上午；UTC 还是 11 月 4 日晚上，目录要按东八区的日期分 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T23:30:00Z"), ZoneId.of("Asia/Shanghai"));

    private static final FileUrlSigner SIGNER = new FileUrlSigner("test-file-sign-key-0123456789abcdef", CLOCK);

    /** JUnit 给每个测试一个临时目录，测完自动删掉 */
    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path tempDir;

    /** 生成缩略图要用 java.awt；应用里 Spring Boot 已经设了无头模式，单元测试不起 Spring，自己设 */
    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 规则：存到 上传目录/年/月/日/随机名.扩展名（扩展名转小写），返回 /files/ 开头、带签名的地址（架构 §8），
     * 过期时间是 7 天后的零点：2026-11-12 00:00 东八区
     */
    @Test
    void upload_ok_savesUnderDatePath() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "说明.PDF", "application/pdf", PDF);

        FileVO vo = service.upload(file);

        assertThat(vo.url()).matches("/files/2026/11/05/[0-9a-f-]{36}\\.pdf\\?e=1794412800&s=[\\w-]{22}");
        assertThat(vo.thumbUrl()).isNull();                       // pdf 不做缩略图
        assertThat(vo.name()).isEqualTo("说明.PDF");
        assertThat(vo.size()).isEqualTo(PDF.length);
        assertThat(Files.readAllBytes(saved(vo.url()))).isEqualTo(PDF);
    }

    /**
     * 规则：导出 PDF 时按地址找上传的文件，带不带签名都认；外部地址、文件不在了、../ 跑出上传目录的都是空，
     * 不能借这个读服务器上别的文件
     */
    @Test
    void localPath_onlyInsideUploadDir() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        FileVO vo = service.upload(new MockMultipartFile("file", "说明.pdf", "application/pdf", PDF));
        Files.writeString(tempDir.getParent().resolve("secret-" + tempDir.getFileName() + ".txt"), "x");

        assertThat(service.localPath(vo.url())).contains(saved(vo.url()));
        assertThat(service.localPath(SIGNER.strip(vo.url()))).contains(saved(vo.url()));
        assertThat(service.localPath("/files/2026/11/05/none.pdf")).isEmpty();
        assertThat(service.localPath("/files/../secret-" + tempDir.getFileName() + ".txt")).isEmpty();
        assertThat(service.localPath("https://example.com/a.pdf")).isEmpty();
        assertThat(service.localPath(null)).isEmpty();
        Files.delete(tempDir.getParent().resolve("secret-" + tempDir.getFileName() + ".txt"));
    }

    /** 规则（RFC 001 §10）：jpg 在旁边生成 _s 缩略图，长边 320、比例不变，返回 thumbUrl；缩略图是另一个地址，单独签名 */
    @Test
    void upload_jpg_createsThumbnail() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "面料.JPG", "image/jpeg", image(1000, 500, "jpg", false));

        FileVO vo = service.upload(file);

        assertThat(vo.thumbUrl()).isEqualTo(SIGNER.sign(SIGNER.strip(vo.url()).replace(".jpg", "_s.jpg")));
        BufferedImage thumb = ImageIO.read(saved(vo.thumbUrl()).toFile());
        assertThat(thumb.getWidth()).isEqualTo(320);
        assertThat(thumb.getHeight()).isEqualTo(160);
        assertThat(ImageIO.read(saved(vo.url()).toFile()).getWidth()).isEqualTo(1000);   // 原图原样存着
    }

    /** 规则：png 的缩略图还是 png，透明的地方保持透明；竖图按高算长边 */
    @Test
    void upload_png_keepsAlpha() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "截图.png", "image/png", image(400, 800, "png", true));

        FileVO vo = service.upload(file);

        assertThat(SIGNER.strip(vo.thumbUrl())).endsWith("_s.png");
        BufferedImage thumb = ImageIO.read(saved(vo.thumbUrl()).toFile());
        assertThat(thumb.getWidth()).isEqualTo(160);
        assertThat(thumb.getHeight()).isEqualTo(320);
        assertThat(thumb.getColorModel().hasAlpha()).isTrue();
        assertThat(new Color(thumb.getRGB(5, 5), true).getAlpha()).isZero();
    }

    /** 规则：本来就比 320 小的图不放大，按原尺寸存一份缩略图（前端统一用 thumbUrl） */
    @Test
    void upload_smallImage_notEnlarged() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "小图.jpg", "image/jpeg", image(100, 60, "jpg", false));

        FileVO vo = service.upload(file);

        BufferedImage thumb = ImageIO.read(saved(vo.thumbUrl()).toFile());
        assertThat(thumb.getWidth()).isEqualTo(100);
        assertThat(thumb.getHeight()).isEqualTo(60);
    }

    /** 上传格式按内容校验；伪装成图片的字节被拒，原图和半截缩略图都不能留下。 */
    @Test
    void upload_brokenImage_rejectedWithoutFiles() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "坏图.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertThatThrownBy(() -> service.upload(file)).isInstanceOf(BizException.class).hasMessageContaining("文件内容与扩展名");
        try (var files = Files.walk(tempDir)) { assertThat(files.filter(Files::isRegularFile)).isEmpty(); }
    }

    /** 头是好的、后面截断的图：格式校验能过、缩略图解不开，报"图片打不开"（不是服务器出错），不留文件 */
    @Test
    void upload_truncatedImage_rejectedAsBizError() throws Exception {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        byte[] whole = image(600, 400, "png", false);
        byte[] half = java.util.Arrays.copyOf(whole, 120);

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "半截.png", "image/png", half)))
                .isInstanceOf(BizException.class).hasMessageContaining("图片打不开");
        assertThatThrownBy(() -> service.save("空.png", null)).isInstanceOf(BizException.class).hasMessage("文件不能为空");
        try (var files = Files.walk(tempDir)) { assertThat(files.filter(Files::isRegularFile)).isEmpty(); }
    }

    /** 规则：设计源文件 AI、PSD 和打版的纸样 PRJ（RFC 011 §2.2）收，不做缩略图，最大 100MB；别的类型还是 10MB，超了 400 说清楚 */
    @Test
    void upload_designSource_largerLimit() {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        long eleven = 11L * 1024 * 1024;

        FileVO psd = service.upload(sized("款式图稿.PSD", eleven));
        assertThat(psd.url()).contains(".psd?");
        assertThat(psd.thumbUrl()).isNull();
        assertThat(service.upload(sized("印花.ai", eleven)).url()).contains(".ai?");
        assertThat(service.upload(sized("裤子纸样.prj", eleven)).url()).contains(".prj?");
        assertThat(service.upload(sized("裤子纸样.plt", eleven)).url()).contains(".plt?");

        assertThatThrownBy(() -> service.upload(sized("大图.jpg", eleven)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400))
                .hasMessage("大图.jpg 太大：图片、PDF、Word、Excel 最大 10MB");
        assertThatThrownBy(() -> service.upload(sized("超大.psd", FileServiceImpl.MAX_DESIGN_SOURCE_BYTES + 1)))
                .hasMessage("超大.psd 太大：AI、PSD、PRJ、PLT 最大 100MB");
    }

    /** 规则：不在白名单里的扩展名（比如 html）不收 */
    @Test
    void upload_htmlFile_rejected() {
        FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "x.html", "text/html", "<script>".getBytes());

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(BizException.class)
                .hasMessage("不支持的文件类型: x.html");
    }

    @Test
    void upload_directorySymlink_cannotCreateDirectoriesOutsideRoot() throws Exception {
        Path outside = Files.createTempDirectory("upload-outside-");
        try {
            Files.createSymbolicLink(tempDir.resolve("2026"), outside);
            FileServiceImpl service = new FileServiceImpl(new LocalUploadStore(tempDir.toString(), CLOCK), mock(UploadCatalog.class), SIGNER);
            assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", "说明.pdf", "application/pdf", PDF)))
                    .isInstanceOf(java.io.UncheckedIOException.class);
            try (var entries = Files.list(outside)) { assertThat(entries).isEmpty(); }
        } finally { Files.delete(outside); }
    }

    private Path saved(String url) {
        return tempDir.resolve(SIGNER.strip(url).substring(FileUrlSigner.PREFIX.length()));
    }

    /** 画一张图：左上角一块透明（png）或白色，其余是铜褐色 */
    private static byte[] image(int width, int height, String format, boolean alpha) throws IOException {
        BufferedImage img = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean corner = x < width / 4 && y < height / 4;
                img.setRGB(x, y, corner ? (alpha ? 0x00000000 : 0xFFFFFFFF) : 0xFF8B5A2B);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** 大小元数据单独测，格式头必须有效；保存时仍以实际读取字节为准。 */
    private static MockMultipartFile sized(String name, long size) {
        byte[] header = new byte[32];
        if (name.toLowerCase().endsWith(".ai")) header = "%!PS-Adobe-3.0".getBytes(StandardCharsets.US_ASCII);
        else { header[0]='8'; header[1]='B'; header[2]='P'; header[3]='S'; header[5]=1; header[17]=1; header[21]=1; }
        return new MockMultipartFile("file", name, "application/octet-stream", header) {
            @Override
            public long getSize() {
                return size;
            }
        };
    }
}
