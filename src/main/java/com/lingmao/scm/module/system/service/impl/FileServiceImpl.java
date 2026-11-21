package com.lingmao.scm.module.system.service.impl;

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
    static final long MAX_BYTES = 10L * 1024 * 1024;
    static final long MAX_DESIGN_SOURCE_BYTES = 100L * 1024 * 1024;
    private final UploadStore storage;
    private final UploadCatalog catalog;
    private final FileUrlSigner signer;
    private final Semaphore capacity = new Semaphore(2);

    @Override public FileVO upload(MultipartFile file) {
        return store(file, false);
    }

    @Override public FileVO save(String fileName, byte[] content) {
        return store(new BytesFile(fileName, content), true);
    }

    private FileVO store(MultipartFile file, boolean wait) {
        if (file == null || file.isEmpty()) throw new BizException("文件不能为空");
        String original = safeName(file.getOriginalFilename());
        String ext = StringUtils.getFilenameExtension(original);
        if (ext == null || !ALLOWED.contains(ext.toLowerCase(Locale.ROOT))) throw new BizException("不支持的文件类型: " + original);
        ext = ext.toLowerCase(Locale.ROOT);
        boolean source = DESIGN_SOURCE.contains(ext);
        long limit = source ? MAX_DESIGN_SOURCE_BYTES : MAX_BYTES;
        if (file.getSize() > limit) throw new BizException(400, original + " 太大：" + (source ? "AI、PSD、PRJ、PLT 最大 100MB" : "图片、PDF、Word、Excel 最大 10MB"));
        if (!acquire(wait)) throw new BizException(429, "正在处理其他文件，请稍后重试");
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
            capacity.release();
        }
    }
    private boolean acquire(boolean wait) {
        if (!wait) return capacity.tryAcquire();
        try { return capacity.tryAcquire(30, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    /** save 用：内存里的字节当成一个上传文件，存储那边只读 getInputStream */
    private record BytesFile(String fileName, byte[] content) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return fileName; }
        @Override public String getContentType() { return null; }
        @Override public boolean isEmpty() { return content.length == 0; }
        @Override public long getSize() { return content.length; }
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
