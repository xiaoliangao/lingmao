package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.IntegrationTestConfig;
import com.lingmao.scm.common.exception.GlobalExceptionHandler;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.scope.DeploymentNamespace;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.UploadCatalog;
import com.lingmao.scm.module.system.service.UploadStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
@Import(IntegrationTestConfig.class)
class A2UploadLifecycleIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired FileService files;
    @Autowired UploadStore store;
    @Autowired UploadCatalog catalog;
    @Autowired JdbcUploadCatalog cleaner;
    @Autowired FileUrlSigner signer;
    @Autowired DeploymentNamespace namespace;
    @Autowired PlatformTransactionManager transactions;
    @Autowired GlobalExceptionHandler errors;
    @Value("${lingmao.upload.dir}") String directory;
    private static final byte[] PDF = "%PDF-1.7\n%%EOF".getBytes(StandardCharsets.US_ASCII);

    @Test void actualHttpUploadChecksContentAndSignedDownloadsCarrySafeHeaders() throws Exception {
        String user = "a2file_" + UUID.randomUUID().toString().substring(0, 12);
        String password = UUID.randomUUID() + "-Pass9";
        jdbc.update("INSERT INTO sys_user(username,password,nickname) VALUES(?,?,?)", user, passwords.encode(password), "文件合成账号");
        try (var client = HttpClient.newHttpClient()) {
            var login = client.send(HttpRequest.newBuilder(uri("/api/auth/login")).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("username", user, "password", password)))).build(), HttpResponse.BodyHandlers.ofString());
            String token = json.readTree(login.body()).get("data").asString();
            var rejected = upload(client, token, "picture.jpg", "<svg>synthetic</svg>".getBytes(StandardCharsets.UTF_8));
            assertThat(json.readTree(rejected.body()).get("code").intValue()).isNotZero();
            assertThat(rejected.headers().firstValue("X-Request-Id")).isPresent();
            var response = upload(client, token, "合成附录（测试）.pdf", PDF);
            var body = json.readTree(response.body()); assertThat(body.get("code").intValue()).isZero();
            String url = body.get("data").get("url").asString();
            String clean = signer.strip(url);
            assertThat(jdbc.queryForObject("SELECT status FROM sys_upload_file WHERE file_url=?", String.class, clean)).isEqualTo("READY");
            assertThat(jdbc.queryForObject("SELECT byte_size FROM sys_upload_file WHERE file_url=?", Long.class, clean)).isEqualTo(PDF.length);
            var downloaded = client.send(HttpRequest.newBuilder(uri(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200); assertThat(downloaded.body()).isEqualTo(PDF);
            assertThat(downloaded.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
            assertThat(downloaded.headers().firstValue("Content-Security-Policy").orElseThrow()).contains("sandbox");
            assertThat(downloaded.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment", "filename*=UTF-8''");
            age(clean); cleaner.run(); assertThat(files.localPath(url)).isEmpty();
        }
    }

    @Test void cleanupPreservesEveryReferenceIncludingAThumbnailUsedAsTheMainFile() throws Exception {
        var image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out);
        var uploaded = files.upload(new MockMultipartFile("file", "合成.png", "image/png", out.toByteArray()));
        String url = signer.strip(uploaded.url()), thumb = signer.strip(uploaded.thumbUrl());
        long attachment = attach(thumb);
        age(url); cleaner.run();
        assertThat(files.localPath(url)).isPresent(); assertThat(files.localPath(thumb)).isPresent();
        jdbc.update("DELETE FROM biz_attachment WHERE id=?", attachment);
        age(url); cleaner.run();
        assertThat(files.localPath(url)).isEmpty(); assertThat(files.localPath(thumb)).isEmpty();
        Path legacy = Path.of(directory).resolve("legacy-" + UUID.randomUUID() + ".pdf");
        Files.createDirectories(legacy.getParent()); Files.write(legacy, PDF);
        Files.setLastModifiedTime(legacy, java.nio.file.attribute.FileTime.fromMillis(1));
        try { cleaner.run(); assertThat(legacy).exists(); } finally { Files.deleteIfExists(legacy); }
    }

    @Test void interruptedWritingIsRecoveredAndContentSizeDoesNotTrustMetadata() throws Exception {
        var slot = store.allocate("pdf"); catalog.begin(slot);
        Path partial = Path.of(directory).toAbsolutePath().resolve(slot.url().substring(FileUrlSigner.PREFIX.length()) + ".part");
        Files.createDirectories(partial.getParent()); Files.write(partial, PDF);
        age(slot.url()); cleaner.run(); assertThat(partial).doesNotExist();
        byte[] oversized = new byte[(int) FileServiceImpl.MAX_BYTES + 1];
        var misleading = new MockMultipartFile("file", "large.pdf", "application/pdf", oversized) {
            @Override public long getSize() { return 1; }
        };
        assertThat(catchThrowable(() -> files.upload(misleading))).hasMessageContaining("实际大小超过");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_upload_file WHERE status='WRITING'", Long.class)).isZero();
    }

    @Test void committingReferenceAndCleanerUseTheSameRealDatabaseLock() throws Exception {
        var uploaded = files.upload(new MockMultipartFile("file", "race.pdf", "application/pdf", PDF));
        String url = signer.strip(uploaded.url()); age(url);
        var locked = new CountDownLatch(1); var commit = new CountDownLatch(1);
        var tx = new TransactionTemplate(transactions); tx.setTimeout(8);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> tx.execute(status -> {
                long attachment = attach(url); locked.countDown(); await(commit); return attachment;
            }));
            try {
                assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
                pool.submit(cleaner::run).get(3, TimeUnit.SECONDS);
                assertThat(files.localPath(url)).isPresent();
            } finally { commit.countDown(); }
            long attachment = writer.get(3, TimeUnit.SECONDS);
            age(url); cleaner.run(); assertThat(files.localPath(url)).isPresent();
            jdbc.update("DELETE FROM biz_attachment WHERE id=?", attachment);
        }
        age(url); cleaner.run(); assertThat(files.localPath(url)).isEmpty();
    }

    @Test void onceCleanupClaimsAFileANewReferenceFailsBeforeFilesystemDeletion() throws Exception {
        var uploaded = files.upload(new MockMultipartFile("file", "claimed.pdf", "application/pdf", PDF));
        String url = signer.strip(uploaded.url()); age(url);
        var claimed = new CountDownLatch(1); var release = new CountDownLatch(1);
        UploadStore delayed = new UploadStore() {
            @Override public Slot allocate(String ext) { return store.allocate(ext); }
            @Override public Stored write(Slot slot, org.springframework.web.multipart.MultipartFile source, long max) throws IOException { return store.write(slot, source, max); }
            @Override public Optional<Path> localPath(String path) { return store.localPath(path); }
            @Override public void delete(Slot slot) throws IOException {
                if (slot.url().equals(url)) { claimed.countDown(); await(release); }
                store.delete(slot);
            }
        };
        var isolatedCleaner = new JdbcUploadCatalog(jdbc, namespace, delayed);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var cleanup = pool.submit(isolatedCleaner::run);
            try {
                assertThat(claimed.await(3, TimeUnit.SECONDS)).isTrue();
                Throwable failure = catchThrowable(() -> attach(url)); assertThat(failure).isNotNull();
                assertThat(errors.handleOther((Exception) failure, new MockHttpServletRequest("POST", "/api/attachments")).getCode()).isEqualTo(1002);
            } finally { release.countDown(); }
            cleanup.get(3, TimeUnit.SECONDS);
        }
        assertThat(files.localPath(url)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_upload_file WHERE file_url=?", String.class, url)).isEqualTo("DELETED");
    }

    private long attach(String url) {
        return jdbc.queryForObject("INSERT INTO biz_attachment(doc_type,doc_id,file_url,file_name) VALUES('STYLE',0,?,'合成附件') RETURNING id", Long.class, url);
    }
    private void age(String url) { jdbc.update("UPDATE sys_upload_file SET touched_at=clock_timestamp()-interval '25 hours' WHERE file_url=?", url); }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test barrier timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    }
    private HttpResponse<String> upload(HttpClient client, String token, String name, byte[] bytes) throws Exception {
        String boundary = "a2-" + UUID.randomUUID();
        var output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes); output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return client.send(HttpRequest.newBuilder(uri("/api/files")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
}
