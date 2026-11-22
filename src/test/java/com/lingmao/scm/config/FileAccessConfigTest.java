package com.lingmao.scm.config;

import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.system.controller.FileController;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.AiSwitchService;
import com.lingmao.scm.module.system.service.PermissionService;
import com.lingmao.scm.security.JwtUtil;
import com.lingmao.scm.security.LoginSessionStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 开发环境的 /files/**（没有 Nginx，应用自己发文件）：带上真的安全配置、签名过滤器和上传目录的映射，
 * 测的是"不用登录、只认签名"。生产的 Nginx 那一侧见 deploy/package/nginx/site.conf
 */
@WebMvcTest(FileController.class)
@Import({SecurityConfig.class, FileAccessConfig.class, FileUrlSigner.class, ClockConfig.class})
class FileAccessConfigTest {

    private static final String KEY = "test-file-sign-key-0123456789abcdef";
    private static final String PATH = "/files/2026/11/15/a.txt";

    /** 上传目录：上下文创建时就要用（WebConfig 读它），等不到 @TempDir 注入，自己建、测完删 */
    private static final Path UPLOADS = createUploads();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileUrlSigner signer;

    /** SecurityConfig 要用，这里用不上，给假的 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private LoginSessionStore loginSessionStore;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private AiSwitchService aiSwitchService;

    @MockitoBean
    private FileService fileService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("lingmao.upload.dir", UPLOADS::toString);
        registry.add("lingmao.upload.sign-key", () -> KEY);
    }

    @AfterAll
    static void cleanUp() throws IOException {
        FileSystemUtils.deleteRecursively(UPLOADS);
    }

    /** 规则：带着签过名的地址，不登录也能拿到文件 */
    @Test
    void signedUrl_withoutLogin_servesFile() throws Exception {
        mockMvc.perform(get(signer.sign(PATH)))
                .andExpect(status().isOk())
                .andExpect(content().string("hello"));
    }

    /** 规则：没签名、签名被改过、拿别的文件的签名来用，一律 403，不给文件 */
    @Test
    void unsignedOrWrongSignature_returns403() throws Exception {
        String other = signer.sign("/files/2026/11/15/b.txt");

        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());
        mockMvc.perform(get(signer.sign(PATH).replaceAll("s=.*", "s=AAAAAAAAAAAAAAAAAAAAAA")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(PATH + other.substring(other.indexOf('?')))).andExpect(status().isForbidden());
    }

    /** 规则：签名对、但过期了，410（和 Nginx 一样），刷新页面拿新地址 */
    @Test
    void expiredUrl_returns410() throws Exception {
        FileUrlSigner lastYear = new FileUrlSigner(KEY,
                Clock.fixed(Instant.parse("2025-11-15T02:00:00Z"), ZoneId.of("Asia/Shanghai")));

        mockMvc.perform(get(lastYear.sign(PATH))).andExpect(status().isGone());
    }

    private static Path createUploads() {
        try {
            Path dir = Files.createTempDirectory("scm-files-");
            Path file = dir.resolve("2026/11/15/a.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "hello");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
