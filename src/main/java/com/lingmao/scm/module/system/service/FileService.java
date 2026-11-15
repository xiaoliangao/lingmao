package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.FileVO;
import org.springframework.web.multipart.MultipartFile;

/** 文件上传：存到服务器本地目录，返回带签名的 /files/... 访问地址（FileUrlSigner） */
public interface FileService {

    FileVO upload(MultipartFile file);
}
