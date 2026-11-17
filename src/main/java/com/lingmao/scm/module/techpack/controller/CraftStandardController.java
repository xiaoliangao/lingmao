package com.lingmao.scm.module.techpack.controller;

import com.lingmao.scm.common.log.OperLog;
import com.lingmao.scm.common.result.R;
import com.lingmao.scm.module.biz.dto.AttachmentReq;
import com.lingmao.scm.module.biz.dto.AttachmentVO;
import com.lingmao.scm.module.techpack.dto.CraftStandardVO;
import com.lingmao.scm.module.techpack.dto.CraftTextsReq;
import com.lingmao.scm.module.techpack.dto.SizePointAddReq;
import com.lingmao.scm.module.techpack.dto.SizePointMethodsReq;
import com.lingmao.scm.module.techpack.service.CraftStandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工艺标准（docs/api/01 §3.14，资料包页签上拉出的抽屉）。改了只影响以后新建的资料包 */
@Tag(name = "工艺标准")
@RestController
@RequestMapping("/api/craft-standards")
@RequiredArgsConstructor
public class CraftStandardController {

    private final CraftStandardService craftStandardService;

    @Operation(summary = "全部内容：文件、九段默认文字、默认包装方式、部位和测量方法")
    @GetMapping
    @PreAuthorize("hasAuthority('techpack:standard:list')")
    public R<CraftStandardVO> get() {
        return R.ok(craftStandardService.get());
    }

    @Operation(summary = "文件整组替换（顺序就是导出时附页的顺序；只收 PDF 和 jpg、png）")
    @PutMapping("/files")
    @PreAuthorize("hasAuthority('techpack:standard:edit')")
    @OperLog(module = "工艺标准", action = "改文件")
    public R<List<AttachmentVO>> replaceFiles(@RequestBody List<@Valid AttachmentReq> files) {
        return R.ok(craftStandardService.replaceFiles(files));
    }

    @Operation(summary = "九段默认文字、默认包装方式（整份覆盖）")
    @PutMapping("/texts")
    @PreAuthorize("hasAuthority('techpack:standard:edit')")
    @OperLog(module = "工艺标准", action = "改默认文字")
    public R<Void> saveTexts(@Valid @RequestBody CraftTextsReq req) {
        craftStandardService.saveTexts(req);
        return R.ok();
    }

    @Operation(summary = "改几个部位的默认测量方法（只改传了的）")
    @PutMapping("/points")
    @PreAuthorize("hasAuthority('techpack:standard:edit')")
    @OperLog(module = "工艺标准", action = "改测量方法")
    public R<Void> saveMethods(@Valid @RequestBody SizePointMethodsReq req) {
        craftStandardService.saveMethods(req.getPoints());
        return R.ok();
    }

    @Operation(summary = "加一个部位（进字典 size_point；同名的已经有了 1000）")
    @PostMapping("/points")
    @PreAuthorize("hasAuthority('techpack:standard:edit')")
    @OperLog(module = "工艺标准", action = "加部位")
    public R<Void> addPoint(@Valid @RequestBody SizePointAddReq req) {
        craftStandardService.addPoint(req.getLabel(), req.getMethod());
        return R.ok();
    }
}
