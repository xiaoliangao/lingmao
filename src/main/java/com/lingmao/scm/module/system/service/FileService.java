package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.FileVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Optional;

/** 文件上传：存到服务器本地目录，返回带签名的 /files/... 访问地址（FileUrlSigner） */
public interface FileService {

    FileVO upload(MultipartFile file);

    /**
     * 本系统上传的文件在服务器上的位置（导出 PDF 时直接读，不走 HTTP）。地址带不带签名都行；
     * 不是 /files/ 开头的、文件已经不在了、拼出来跑出上传目录的（../），返回空
     */
    Optional<Path> localPath(String url);
}
