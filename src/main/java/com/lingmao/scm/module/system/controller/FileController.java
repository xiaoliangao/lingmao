package com.lingmao.scm.module.system.controller;

import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.system.dto.FileVO;
import com.lingmao.scm.module.system.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "文件")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @Operation(summary = "上传一个文件（登录即可），返回访问地址")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<FileVO> upload(@RequestParam("file") MultipartFile file) {
        return R.ok(fileService.upload(file));
    }
}
