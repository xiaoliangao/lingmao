package com.lingmao.scm.module.system.service;

import com.lingmao.scm.module.system.dto.FileVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.Optional;

/** 文件上传：存到服务器本地目录，返回带签名的 /files/... 访问地址（FileUrlSigner） */
public interface FileService {

    FileVO upload(MultipartFile file);

    /**
     * 存一个不是从页面上传来的文件（导入时从 Excel 里取出来的图片）：检查、缩略图、地址都和 upload 一样。
     * 和别的上传一起排队，忙的时候等一会儿（最多 30 秒），不像页面上传那样马上退回。
     * 存了没被引用的文件 24 小时后自动清掉，所以可以在写库之前存
     */
    FileVO save(String fileName, byte[] content);

    /**
     * 本系统上传的文件在服务器上的位置（在页面里预览 Excel 时直接读，不走 HTTP）。地址带不带签名都行；
     * 不是 /files/ 开头的、文件已经不在了、拼出来跑出上传目录的（../），返回空
     */
    Optional<Path> localPath(String url);
}
