package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.common.file.FileUrlSigner;
import com.lingmao.scm.common.image.Thumbnails;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class FileServiceImpl implements FileService {

    /** 只收这些扩展名。不收 html、js、exe 这类能在浏览器里执行或被下载运行的文件 */
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "xls", "xlsx", "doc", "docx");
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path root;
    private final Clock clock;
    private final FileUrlSigner signer;

    public FileServiceImpl(@Value("${lingmao.upload.dir}") String uploadDir, Clock clock, FileUrlSigner signer) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.clock = clock;
        this.signer = signer;
    }

    @Override
    public FileVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件不能为空");
        }
        String original = file.getOriginalFilename();
        String ext = StringUtils.getFilenameExtension(original);
        if (ext == null || !ALLOWED.contains(ext.toLowerCase())) {
            throw new BizException("不支持的文件类型: " + original);
        }
        ext = ext.toLowerCase();
        // 存储用的文件名自己生成（UUID），不用用户给的名字：防重名，也防 ../ 这类路径穿越
        // 目录按上传那天分，"今天"从注入的 Clock 取（RFC 001 §6.2）
        String datePath = LocalDate.now(clock).format(DATE_PATH);
        String baseName = UUID.randomUUID().toString();
        Path dir = root.resolve(datePath);
        Path target = dir.resolve(baseName + "." + ext);
        try {
            Files.createDirectories(dir);
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败: " + target, e);
        }
        String thumbName = Thumbnails.supports(ext) ? thumbnail(target, dir.resolve(baseName + "_s." + ext), ext) : null;
        // 返回的地址带签名（架构 §8）；缩略图是另一个地址，单独签
        return new FileVO(signer.sign(urlOf(datePath, target.getFileName().toString())),
                thumbName == null ? null : signer.sign(urlOf(datePath, thumbName)), original, file.getSize());
    }

    /**
     * 在原图旁边生成缩略图（文件名加 _s），返回它的文件名。生成不了（坏图、格式不认得）只记 warn、返回 null：
     * 原图已经存好了，上传照样算成功，前端没有缩略图就显示原图（RFC 001 §10）
     */
    private String thumbnail(Path source, Path thumb, String ext) {
        try {
            Thumbnails.create(source, thumb, ext);
            return thumb.getFileName().toString();
        } catch (IOException | RuntimeException e) {
            log.warn("生成缩略图失败，只存原图: {}，原因: {}", source.getFileName(), e.toString());
            try {
                // 写了一半的缩略图删掉，免得留下一张打不开的 _s
                Files.deleteIfExists(thumb);
            } catch (IOException ignored) {
                // 删不掉也不影响：没人引用它
            }
            return null;
        }
    }

    private static String urlOf(String datePath, String fileName) {
        return FileUrlSigner.PREFIX + datePath + "/" + fileName;
    }
}
