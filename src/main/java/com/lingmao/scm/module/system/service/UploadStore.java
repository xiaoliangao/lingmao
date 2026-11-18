package com.lingmao.scm.module.system.service;

import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** 上传的文件不可变；存储变化不改业务附件的引用。 */
public interface UploadStore {
    record Slot(String url, String thumbnailUrl) {}
    record Stored(long bytes, String sha256, String thumbnailUrl) {}
    Slot allocate(String extension);
    Stored write(Slot slot, MultipartFile source, long maxBytes) throws IOException;
    void delete(Slot slot) throws IOException;
    Optional<Path> localPath(String url);
}
