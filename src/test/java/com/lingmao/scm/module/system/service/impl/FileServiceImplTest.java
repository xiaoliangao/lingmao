package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.system.dto.FileVO;
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
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "说明.PDF", "application/pdf", new byte[]{1, 2, 3});

        FileVO vo = service.upload(file);

        assertThat(vo.url()).matches("/files/2026/11/05/[0-9a-f-]{36}\\.pdf\\?e=1794412800&s=[\\w-]{22}");
        assertThat(vo.thumbUrl()).isNull();                       // pdf 不做缩略图
        assertThat(vo.name()).isEqualTo("说明.PDF");
        assertThat(vo.size()).isEqualTo(3);
        assertThat(Files.readAllBytes(saved(vo.url()))).containsExactly(1, 2, 3);
    }

    /**
     * 规则：导出 PDF 时按地址找上传的文件，带不带签名都认；外部地址、文件不在了、../ 跑出上传目录的都是空，
     * 不能借这个读服务器上别的文件
     */
    @Test
    void localPath_onlyInsideUploadDir() throws Exception {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        FileVO vo = service.upload(new MockMultipartFile("file", "说明.pdf", "application/pdf", new byte[]{1}));
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
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
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
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
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
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "小图.jpg", "image/jpeg", image(100, 60, "jpg", false));

        FileVO vo = service.upload(file);

        BufferedImage thumb = ImageIO.read(saved(vo.thumbUrl()).toFile());
        assertThat(thumb.getWidth()).isEqualTo(100);
        assertThat(thumb.getHeight()).isEqualTo(60);
    }

    /** 规则：缩略图生成不了（坏图、只有扩展名是 jpg），原图照样存好、上传成功，thumbUrl 是 null，不留半截 _s 文件 */
    @Test
    void upload_brokenImage_stillSucceedsWithoutThumbnail() throws Exception {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "坏图.jpg", "image/jpeg", new byte[]{1, 2, 3});

        FileVO vo = service.upload(file);

        assertThat(vo.thumbUrl()).isNull();
        assertThat(Files.readAllBytes(saved(vo.url()))).containsExactly(1, 2, 3);
        try (var files = Files.list(saved(vo.url()).getParent())) {
            assertThat(files).hasSize(1);
        }
    }

    /** 规则：设计源文件 AI、PSD 收，不做缩略图，最大 100MB；别的类型还是 10MB，超了 400 说清楚 */
    @Test
    void upload_designSource_largerLimit() {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        long eleven = 11L * 1024 * 1024;

        FileVO psd = service.upload(sized("款式图稿.PSD", eleven));
        assertThat(psd.url()).contains(".psd?");
        assertThat(psd.thumbUrl()).isNull();
        assertThat(service.upload(sized("印花.ai", eleven)).url()).contains(".ai?");

        assertThatThrownBy(() -> service.upload(sized("大图.jpg", eleven)))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.getCode()).isEqualTo(400))
                .hasMessage("大图.jpg 太大：图片、PDF、Word、Excel 最大 10MB");
        assertThatThrownBy(() -> service.upload(sized("超大.psd", FileServiceImpl.MAX_DESIGN_SOURCE_BYTES + 1)))
                .hasMessage("超大.psd 太大：AI、PSD 最大 100MB");
    }

    /** 规则：不在白名单里的扩展名（比如 html）不收 */
    @Test
    void upload_htmlFile_rejected() {
        FileServiceImpl service = new FileServiceImpl(tempDir.toString(), CLOCK, SIGNER);
        MockMultipartFile file = new MockMultipartFile("file", "x.html", "text/html", "<script>".getBytes());

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(BizException.class)
                .hasMessage("不支持的文件类型: x.html");
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

    /** 报的大小是 size，内容只有 3 个字节：测大小规则不用真的分配几十 MB */
    private static MockMultipartFile sized(String name, long size) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[]{1, 2, 3}) {
            @Override
            public long getSize() {
                return size;
            }
        };
    }
}
