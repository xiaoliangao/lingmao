package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.file.UploadContent;
import com.lingmao.scm.common.image.Thumbnails;
import com.lingmao.scm.module.system.service.UploadStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
public final class LocalUploadStore implements UploadStore {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final long RESERVE_BYTES = 256L * 1024 * 1024;
    private final Path root;
    private final Clock clock;
    public LocalUploadStore(@Value("${lingmao.upload.dir}") String directory, Clock clock) {
        this.root = Path.of(directory).toAbsolutePath().normalize(); this.clock = clock;
    }
    @Override public Slot allocate(String extension) {
        String base = FileUrlSigner.PREFIX + LocalDate.now(clock).format(DATE) + "/" + UUID.randomUUID();
        return new Slot(base + "." + extension, Thumbnails.supports(extension) ? base + "_s." + extension : null);
    }
    @Override public Stored write(Slot slot, MultipartFile source, long maxBytes) throws IOException {
        Path target = resolve(slot.url()), temporary = target.resolveSibling(target.getFileName() + ".part");
        createParent(target); safeParent(target);
        if (Files.getFileStore(root).getUsableSpace() < RESERVE_BYTES + maxBytes) throw new BizException(503, "存储空间不足，请稍后重试");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        long bytes = 0;
        try {
            try (var input = source.getInputStream(); var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    bytes += count;
                    if (bytes > maxBytes) throw new BizException(413, "文件实际大小超过上传上限，请拆分后上传");
                    digest.update(buffer, 0, count); output.write(buffer, 0, count);
                }
            }
            if (bytes == 0) throw new BizException("文件不能为空");
            String extension = slot.url().substring(slot.url().lastIndexOf('.') + 1);
            UploadContent.verify(temporary, extension);
            String thumbnail = null;
            if (slot.thumbnailUrl() != null) {
                Path thumb = resolve(slot.thumbnailUrl()); safeParent(thumb);
                try { Thumbnails.create(temporary, thumb, extension); }
                catch (Thumbnails.Unreadable broken) { throw new BizException(broken.getMessage()); }
                thumbnail = slot.thumbnailUrl();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(bytes, HexFormat.of().formatHex(digest.digest()), thumbnail);
        } finally { Files.deleteIfExists(temporary); }
    }
    @Override public void delete(Slot slot) throws IOException {
        Path target = resolve(slot.url());
        if (Files.exists(target.getParent())) {
            safeParent(target); Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
            if (slot.thumbnailUrl() != null) { Path thumb = resolve(slot.thumbnailUrl()); safeParent(thumb); Files.deleteIfExists(thumb); }
        }
    }
    @Override public Optional<Path> localPath(String url) {
        try {
            Path file = resolve(url);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            safeParent(file); return Optional.of(file);
        } catch (IOException | IllegalArgumentException invalid) { return Optional.empty(); }
    }
    private Path resolve(String url) {
        if (url == null || !url.startsWith(FileUrlSigner.PREFIX) || url.contains("..") || url.indexOf('\\') >= 0
                || url.indexOf('%') >= 0 || url.indexOf('?') >= 0 || url.indexOf('#') >= 0)
            throw new IllegalArgumentException("Invalid upload path");
        Path path = root.resolve(url.substring(FileUrlSigner.PREFIX.length())).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("Invalid upload path");
        return path;
    }
    private void safeParent(Path file) throws IOException {
        if (!file.getParent().toRealPath().startsWith(root.toRealPath())) throw new IOException("Upload directory escapes storage root");
        if (Files.isSymbolicLink(file)) throw new IOException("Upload file must not be a symbolic link");
    }
    private void createParent(Path file) throws IOException {
        Files.createDirectories(root);
        Path directory = root;
        for (Path part : root.relativize(file.getParent())) {
            directory = directory.resolve(part);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Upload directory must not be a symbolic link");
            Files.createDirectories(directory);
            if (!directory.toRealPath().startsWith(root.toRealPath())) throw new IOException("Upload directory escapes storage root");
        }
    }
}
