package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.auth.CallerScope;
import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.FileService;
import com.lingmao.scm.module.system.service.UploadCatalog;
import com.lingmao.scm.module.system.service.UploadStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "xls", "xlsx", "doc", "docx", "ai", "psd", "prj", "plt");
    /** 设计源文件、打版文件（资料包的纸样 .prj、.plt，RFC 011 §2.2、Q48）：大，100MB 以内 */
    private static final Set<String> DESIGN_SOURCE = Set.of("ai", "psd", "prj", "plt");
    /** 工厂账号（RFC 019 §7）只传报工照片：只收图片，10MB 以内 */
    private static final Set<String> IMAGES = Set.of("jpg", "jpeg", "png", "gif", "webp");
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final long MAX_DESIGN_SOURCE_BYTES = 100L * 1024 * 1024;
    /** 导入存图最多等多久（两把信号量合计） */
    static final Duration WAIT = Duration.ofSeconds(30);
    private final UploadStore storage;
    private final UploadCatalog catalog;
    private final FileUrlSigner signer;
    /** 同时最多存两个文件（缩略图吃内存）；页面上传拿不到就 429，不排队 */
    private final Semaphore capacity = new Semaphore(2);
    /** 导入时批量存的图（save）同时只占一个，留一个给页面上传：导入几百张图时别人照样能传 */
    private final Semaphore bulk = new Semaphore(1);

    @Override public FileVO upload(MultipartFile file) {
        return store(file, false);
    }

    @Override public FileVO save(String fileName, byte[] content) {
        return store(new BytesFile(fileName, content), true);
    }

    /** bulk：导入时存的图，先占 bulk 再等 capacity，两把一共最多等 30 秒；页面上传拿不到 capacity 立刻 429 */
    private FileVO store(MultipartFile file, boolean bulkSave) {
        if (file == null || file.isEmpty()) throw new BizException("文件不能为空");
        String original = safeName(file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(original);
        if (ext == null || !ALLOWED.contains(ext.toLowerCase(Locale.ROOT))) throw new BizException("不支持的文件类型: " + original);
        ext = ext.toLowerCase(Locale.ROOT);
        if (!bulkSave && CallerScope.factoryId() != null && !IMAGES.contains(ext)) {
            throw new BizException(400, "工厂账号只能传照片（JPG、PNG、WEBP、GIF）：" + original);
        }
        boolean source = DESIGN_SOURCE.contains(ext);
        long limit = source ? MAX_DESIGN_SOURCE_BYTES : MAX_BYTES;
        if (file.getSize() > limit) throw new BizException(400, original + " 太大：" + (source ? "AI、PSD、PRJ、PLT 最大 100MB" : "图片、PDF、Word、Excel 最大 10MB"));
        long deadline = System.nanoTime() + WAIT.toNanos();
        if (bulkSave && !acquire(bulk, deadline)) throw busy();
        try {
            if (!(bulkSave ? acquire(capacity, deadline) : capacity.tryAcquire())) throw busy();
            try { return write(file, ext, limit, original); }
            finally { capacity.release(); }
        } finally {
            if (bulkSave) bulk.release();
        }
    }

    private FileVO write(MultipartFile file, String ext, long limit, String original) {
        UploadStore.Slot slot = null; boolean saved = false;
        try {
            slot = storage.allocate(ext); catalog.begin(slot);
            UploadStore.Stored stored = storage.write(slot, file, limit);
            catalog.ready(slot, stored); saved = true;
            return new FileVO(signer.sign(slot.url()), signer.sign(stored.thumbnailUrl()), original, stored.bytes());
        } catch (IOException failure) { throw new UncheckedIOException("保存上传文件失败", failure); }
        finally {
            if (!saved && slot != null) {
                try { storage.delete(slot); catalog.failed(slot); }
                catch (IOException | RuntimeException failure) { log.warn("上传文件清理暂未完成，将由后台重试", failure); }
            }
        }
    }
    private static boolean acquire(Semaphore slots, long deadline) {
        try { return slots.tryAcquire(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }
    private static BizException busy() { return new BizException(429, "正在处理其他文件，请稍后重试"); }

    /** save 用：内存里的字节当成一个上传文件，存储那边只读 getInputStream */
    private record BytesFile(String fileName, byte[] content) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return null; }
        @Override public boolean isEmpty() { return content == null || content.length == 0; }
        @Override public long getSize() { return content == null ? 0 : content.length; }
        @Override public byte[] getBytes() { return content.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), content); }
    }

    @Override public Optional<Path> localPath(String url) { return storage.localPath(signer.strip(url)); }
    private static String safeName(String name) {
        String clean = name == null ? "" : name.replace('\\', '/');
        clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (clean.length() > 200) throw new BizException("文件名过长，请缩短后上传");
        return clean;
    }
}
