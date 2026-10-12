package com.lingmao.scm.module.system.service.impl;

import com.lingmao.scm.common.exception.BizException;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.FileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

@Service
public class FileServiceImpl implements FileService {

    /** 只收这些扩展名。不收 html、js、exe 这类能在浏览器里执行或被下载运行的文件 */
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "webp", "pdf", "xls", "xlsx", "doc", "docx");
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Path root;

    public FileServiceImpl(@Value("${lingmao.upload.dir}") String uploadDir) {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
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
        // 存储用的文件名自己生成（UUID），不用用户给的名字：防重名，也防 ../ 这类路径穿越
        String datePath = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DATE_PATH);
        String storedName = UUID.randomUUID() + "." + ext.toLowerCase();
        Path target = root.resolve(datePath).resolve(storedName);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target);
        } catch (IOException e) {
            throw new UncheckedIOException("保存上传文件失败: " + target, e);
        }
        return new FileVO("/files/" + datePath + "/" + storedName, original, file.getSize());
    }
}
